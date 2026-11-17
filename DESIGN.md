# VoiceSim 设计文档

> Android 端实时音色转换（Voice Conversion）框架
> 版本：v0.2（RVC 推理接入） · 需求见 [REQUIREMENTS.md](REQUIREMENTS.md)

---

## 1. 目标与边界

### 1.1 要做的事

- Android 手机上，把麦克风采集的实时语音逐块转换成**目标音色**，保留原说话人的语气、节奏、内容。
- 转换发生在说话输入过程中（边说边转），输出给 App 内的语音对讲/录音链路。
- 全本地推理，不联网，原始音频不落盘。
- 推理后端、模型、音色可替换。

### 1.2 明确不做

- **系统级虚拟麦克风**：让微信/游戏直接采到变声后的声音。Android 没有官方 API，需要 root 或系统签名。
- 端侧训练；训练放在 PC。
- 实时 index 检索融合（手机端 O(N×T×768) 太慢）。
- GPU/QNN 深度适配（P4 再评估）。

### 1.3 硬约束

| 约束 | 取值 |
|---|---|
| 直通延迟 | < 150 ms |
| RVC 端到端延迟 | 1.0–2.0 s（旗舰），2–3 s（中端） |
| RTF（推理/音频） | < 0.8 才能持续实时 |
| 设备 | arm64-v8a，Android 8.0+，RAM ≥ 6 GB |
| 网络 | 完全离线 |
| 采样率 | 内部 48 kHz；模型域 16 kHz / 40–48 kHz |

> 生态调研结论：手机端 RVC 实际都用 0.5–3 s 分块，320 ms 级实时不现实。见 `REQUIREMENTS.md`。

---

## 2. 架构总览

### 2.1 分层

```
┌───────────────────────────────────────────────┐
│ app            界面 / 前台服务 / 权限 / 生命周期  │
├───────────────────────────────────────────────┤
│ core:vc        转换器、RVC 引擎、模型包、管线      │
├──────────────┬────────────────┬───────────────┤
│ core:dsp     │ core:inference │ core:audio     │
│ FFT/Mel/重采样│ 张量/会话/ORT   │ 设备 I/O、队列  │
└──────────────┴────────────────┴───────────────┘
```

依赖方向只能向下：

```
app ──► core:vc ──► core:inference
          │  │
          │  └────► core:dsp
          └───────► core:audio
```

### 2.2 模块职责

| 模块 | 职责 | 对外接口 |
|---|---|---|
| `core:audio` | AudioRecord/AudioTrack、音频格式、有界队列、音频线程 | `AudioSource`、`AudioSink`、`FrameQueue`、`FloatFifo` |
| `core:dsp` | 重采样、Biquad、VAD、YIN、限幅、FFT、RMVPE Mel | `Resampler`、`Biquad`、`PitchEstimator`、`Fft`、`RmvpeMel` |
| `core:inference` | 类型化张量、模型会话、ORT 后端 | `Tensor`、`ModelSession`、`InferenceBackend` |
| `core:vc` | 转换器协议、RVC 引擎、模型清单、实时管线 | `VoiceConverter`、`RvcEngine`、`VoiceChangerPipeline` |
| `app` | Compose 界面、前台服务、控制器 | `VoiceChangerController` |

### 2.3 关键设计原则

1. **音频路径零 Android 依赖下沉**：DSP、推理、转换器都能在 JVM 单测里跑。
2. **转换器可插拔**：`VoiceConverter` 是唯一扩展点。
3. **线程边界用有界队列隔离**：任一环变慢 → 丢最旧帧/欠载，不级联阻塞。
4. **失败必须降级**：推理异常 → 本块直通 + 淡入淡出。
5. **延迟是显式参数**：`hopSamples` 由转换器声明，RVC 由模型 `segment_frames` 决定。

---

## 3. 音频契约

| 项 | 值 |
|---|---|
| 采样率 | 48 000 Hz |
| 声道 | 单声道 |
| 格式 | float32 |
| 帧长 | 20 ms（960 样本） |
| 采集队列 | 64 帧（1.28 s） |
| 播放 FIFO | 2 s |

模型域：

- HuBERT / RMVPE：16 kHz
- RVC Generator：40 kHz 或 48 kHz（读 manifest / config.json）

重采样点只有三处：入口 48k → 16k、模型输出 → 48k、可选的播放设备重采样。

---

## 4. 信号链

```
麦克风
  │ AudioRecord(VOICE_COMMUNICATION, 48k, mono, float)
  ▼
[采集线程] 20 ms 阻塞读 → 帧组装 → captureQueue(64)
  │
  ▼
[转换线程] 累积 hop（模型决定，默认 640 ms）
  ├─ （直通）直接拷贝
  └─ （RVC）
       ├─ 48k → 16k
       ├─ HuBERT → [T,768] → 插值到 64 帧
       ├─ RMVPE（无则自相关）→ F0 → 中值滤波 → 音高偏移
       ├─ text_encoder → m / logs / x_mask
       ├─ flow → z
       ├─ generator → 40k/48k 波形
       ├─ 48k 重采样 + 10 ms 交叉淡化
       └─ 峰值限幅
  │ playbackFifo(2s)
  ▼
[播放线程] 20 ms 拉取 → AudioTrack
```

说明：

- 固定 64 帧（640 ms）是为了让 HuBERT/RMVPE 的固定开销摊薄；缩小到 320 ms 会显著抬高 RTF。
- 块间交叉淡化只解决相位跳变，不解决韵律连续性；真正常驻上下文需要模型支持动态窗口。
- 限幅放在最后，保证生成模型偶发过冲不削爆后级。

---

## 5. 线程模型与背压

| 线程 | 优先级 | 职责 | 队列 | 满/空策略 |
|---|---|---|---|---|
| Capture | URGENT_AUDIO | 阻塞读 AudioRecord | captureQueue | 丢最旧 + 计数 |
| Convert | AUDIO | 累积 hop → 推理 → 写 FIFO | playbackFifo | 覆盖最旧 + 计数 |
| Playback | URGENT_AUDIO | 20 ms 写 AudioTrack | — | 补静音 + underrun 计数 |
| UI | 默认 | StateFlow、界面、服务 | — | — |

音频线程用原生 `Thread`，不用协程。

### 延迟预算（RVC，64 帧）

| 阶段 | 预算 |
|---|---|
| 采集缓冲 | 20 ms |
| hop 累积 | 640 ms |
| 推理 | 400–800 ms（旗舰） |
| 交叉淡化/拼接 | 10 ms |
| 播放缓冲 | 40–80 ms |
| **合计** | **约 1.1–1.6 s** |

中端机 RTF > 1 时，端到端会超过 2 s，需要切 960 ms 块或提示“仅支持离线”。

---

## 6. 转换器

```kotlin
interface VoiceConverter : AutoCloseable {
    val sampleRate: Int
    val hopSamples: Int
    val contextSamples: Int

    fun prepare(profile: VoiceProfile, config: ConversionConfig)
    fun convert(inputWindow: FloatArray, output: FloatArray): Int
    fun reset()
}
```

| 实现 | 状态 | 说明 |
|---|---|---|
| `PassthroughConverter` | ✅ | 20 ms hop，链路验证/兜底 |
| `RvcConverter` | ✅ | 64 帧分块，拆分/合并模型，音高偏移、交叉淡化 |
| `RvcEngine` | ✅ | HuBERT + RMVPE + text_encoder + flow + generator |
| `NcnnRvcConverter` | 预留 | 换 ncnn 后端 |
| `DspConverter` | 预留 | 纯 DSP 变调/共振峰 |

`RvcConverter` 的 hop 以模型 `segment_frames` 为准（64 帧 → 640 ms），忽略 `ConversionConfig.hopMs`。

---

## 7. 模型协议

```
assets/models/<profileId>/
├── manifest.json
├── hubert.onnx            # 必需
├── rmvpe.onnx             # 推荐，缺失回退自相关
├── text_encoder.onnx      # 拆分模型
├── flow.onnx
├── generator.onnx
├── voice.onnx             # 合并模型（二选一）
├── config.json            # 可选，target_sr
└── speaker.bin            # 可选，256 维 float32
```

```json
{
  "id": "demo",
  "name": "示例音色",
  "version": "2.0",
  "sample_rate": 40000,
  "segment_frames": 64,
  "f0": true,
  "speaker_id": 0,
  "mode": "split",
  "files": {
    "hubert": "hubert.onnx",
    "rmvpe": "rmvpe.onnx",
    "text_encoder": "text_encoder.onnx",
    "flow": "flow.onnx",
    "generator": "generator.onnx"
  },
  "combined": "voice.onnx",
  "embedding": "speaker.bin",
  "license": "CC-BY-4.0",
  "source": "https://example.com/model"
}
```

规则：

- `hubert` 必需；`mode=split` 需要 text_encoder/flow/generator；`mode=combined` 需要 combined。
- `segment_frames` 必须是 16 的倍数，建议 64。
- 未写 `license` 的模型不打包进分发版。
- 首次启动从 assets 拷到 `filesDir/models`，已存在不覆盖。

### 公开模型跑通路径

1. 选 RVC v2 公开模型（`.pth`）。
2. PC 导出 ONNX：HuBERT、RMVPE、text_encoder、flow、generator（或合并模型）。
3. 固定 `segment_frames=64` 的窗口，避免 1024 帧固定导出（那样只能离线）。
4. 基础模型：HuBERT / RMVPE ONNX 可从 RVC 生态获取，单个 180–360 MB。
5. 打包到 `assets/models/<id>/` 或 adb push 到 `filesDir/models/<id>/`。

---

## 8. 推理后端

- `Tensor` 支持 float32 / int64 / int32 / bool，覆盖 RVC 的 lengths、pitch、padding_mask。
- 默认 ORT CPU + XNNPACK；NNAPI 可选，失败回退。
- 模型加载在 IO 线程；首帧前 warm-up。
- 线程数默认 2，避免与音频线程抢核。

### 性能预算（64 帧）

| 子模型 | 旗舰 | 中端 |
|---|---|---|
| HuBERT | 200 ms | 400 ms |
| RMVPE | 80 ms | 150 ms |
| text_encoder | 40 ms | 80 ms |
| flow | 40 ms | 80 ms |
| generator | 120 ms | 250 ms |
| 前后处理 | 20 ms | 40 ms |
| **合计** | **500 ms** | **1000 ms** |

---

## 9. 失败与降级

| 场景 | 行为 |
|---|---|
| 推理异常 | 本块直通 + 淡入淡出，`fallbackChunks++` |
| 模型缺失/损坏 | 启动前拦截，音色不可选 |
| 播放欠载 | 补静音，不阻塞 |
| 采集溢出 | 丢最旧帧 |
| 内存不足 | 停止并提示换小模型 |
| 蓝牙 SCO | 重采样兜底，提示音质 |

---

## 10. 隐私

- 全本地推理，无网络权限需求。
- 原始 PCM 只在内存环形缓冲中存在。
- 日志只记录帧数、耗时、丢帧计数。
- 模型和音色可一键删除。

---

## 11. 目录结构

```
VoiceSim/
├── DESIGN.md / REQUIREMENTS.md / README.md
├── settings.gradle.kts
├── app/                       界面、前台服务、控制器
└── core/
    ├── audio/                  设备 I/O、队列、帧组装
    ├── dsp/                    重采样、滤波、VAD、F0、FFT、RMVPE Mel
    ├── inference/              类型化张量、会话、ORT 后端
    └── vc/                     转换器、RVC 引擎、模型清单、管线
```

---

## 12. 路线图

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 骨架 + 直通链路 + 前台服务 + 界面 | ✅ |
| P1 | DSP 基础件（重采样/滤波/VAD/YIN/限幅） | ✅ |
| P2 | RVC 拆分/合并模型推理、音高、交叉淡化 | ✅ |
| P3 | int8/NNAPI、索引、性能与发热优化 | 待做 |
| P4 | 训练与音色导入、QNN | 待做 |
| P5 | 对讲传输（WebRTC/WebSocket）、PTT、AEC | 待做 |

---

## 13. 风险

| 风险 | 影响 | 对策 |
|---|---|---|
| 中端机 RTF > 1 | 无法实时 | 切 960 ms 块、降级直通、明确设备门槛 |
| 模型输入形状不匹配 | 推理失败 | 加载时校验，错误信息指向导出参数 |
| HuBERT 360 MB | 内存/加载慢 | 常驻单例、int8 |
| NNAPI 算子不支持 | 回退 CPU | 默认 XNNPACK |
| 声学反馈 | 啸叫 | 强制耳机、提示 |
| 模型许可 | 无法分发 | manifest 强制 license |

---

## 14. 验收（M2）

1. 放入拆分 RVC v2 模型后 3 s 内可开始转换。
2. 说话能听出目标音色，内容可懂。
3. 统计面板显示分块、RTF、丢帧、欠载。
4. 旗舰机 RTF < 1；中端机不崩、可降级。
5. 连续 10 min 无 OOM/ANR。
