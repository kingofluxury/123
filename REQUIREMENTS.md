# VoiceSim 需求规格与开发拆解（v0.2）

> 基于 2026-11 对 GitHub 同类项目的调研（MeowRVC、Hybrid-VC-for-Android、ultimate-rvc-mobile、atri-vc、ouor/RVC-Android）修订。
> 上一版 `DESIGN.md` 的 320 ms hop 实时目标过于乐观，本版按实测生态调整为 **1–2 秒级**，并支持拆分/合并两种 RVC 模型。

---

## 1. 背景与定位

### 1.1 要解决的问题

在 Android 手机上，把说话人的声音**实时转换成某个特定人的音色**，用于 App 内语音对讲：A 说话 → 端侧转换 → 编码上行 → B 听到的是目标音色。

### 1.2 与同类项目的差异

| 项目 | 做法 | 我们的差异 |
|---|---|---|
| MeowRVC | 悬浮窗 + 扬声器/麦克风声学耦合，需 root，定位演示 | 应用内直接拿 PCM，不走声学耦合 |
| Hybrid-VC | so-vits 4.0 + QNN NPU，3 s 分片，Termux 构建 | 标准 Gradle，ORT CPU 优先，先跑通再谈 NPU |
| ultimate-rvc-mobile | Flutter，离线完整，实时页约 6 s 卡一次 | 模块化 Kotlin 管线，实时优先 |
| atri-vc | 离线转换，受限于固定 1024 帧导出 | 强制要求可变/短窗口导出，协议里写死 |
| ouor/RVC-Android | 离线文件转换 | 实时 + 对讲 |

**结论：没有完全一样的项目。我们保留自研骨架，复用 MeowRVC 已验证的 RVC 推理链路（Apache-2.0）。**

### 1.3 产品边界

**做：**

- 端侧、离线、应用内实时音色转换。
- RVC v2 模型（拆分三件套或合并单模型）。
- 对讲上行 PCM 输出、耳机监听、基础参数调节。
- 失败降级、性能诊断、模型管理。

**不做（本版本）：**

- 系统级虚拟麦克风（需 root / 系统签名）。
- 端侧训练、多说话人分离。
- 实时变调/共振峰 DSP 的高级音质（P1 已有基础模块，后续接入）。
- GPU/NPU 深度适配（QNN/HTP 留到 P4）。
- 索引检索（index）实时融合：手机端 O(N×T×768) 太慢，先不支持。

---

## 2. 用户与场景

### 2.1 角色

- **主播/客服/游戏玩家**：希望对外输出另一个音色。
- **开发者**：接入自有 VoIP / 对讲 SDK，需要 PCM 级 API。

### 2.2 核心场景

1. **应用内对讲（主场景）**
   B 端收到 A 的语音；A 端在按下 PTT 时，麦克风 PCM 经过变声后交给传输层。半双工，B 端不参与转换。
2. **本地监听**
   戴耳机说话，实时听到目标音色，用于调参和验收。
3. **录音转换（后续）**
   录一段 → 转换 → 试听/保存，作为无实时条件下的兜底。

### 2.3 用户操作路径

```
安装 → 授权录音/通知 → 导入音色模型 → 选择音色 → 选择延迟档 →
戴耳机 → 开始 → 说话 → 听到目标音色 → 调节音高/增益
```

---

## 3. 功能需求

### FR-1 音色模型管理

- 支持从 `assets/models/<id>/` 首次导入到 `filesDir/models/<id>/`。
- 支持从系统文件选择器导入一个目录/ZIP（P3，本期先支持 adb push 到应用私有目录）。
- 列出、选择、删除音色；显示大小、采样率、许可、来源。
- 删除时清理 manifest、ONNX、embedding、index。

### FR-2 模型格式

必须支持两种布局：

**A. 拆分模型（推荐，MeowRVC/主流导出）**

```
<id>/
├── manifest.json
├── hubert.onnx            # 必需，16 kHz 内容特征
├── rmvpe.onnx             # 音高，推荐；缺失时回退自相关
├── text_encoder.onnx      # phone + pitch -> m/logs/x_mask
├── flow.onnx              # z_p + x_mask + g -> z
├── generator.onnx         # z + f0 + g -> audio
├── config.json            # 可选，target_sr
└── speaker.bin            # 可选，256 维 float32 小端
```

**B. 合并模型**

```
<id>/
├── manifest.json
├── hubert.onnx
├── rmvpe.onnx
├── <voice>.onnx           # phone/phone_lengths/pitch/nsff0/sid -> audio[, sr]
└── config.json
```

`manifest.json` 新增字段：

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

校验规则：

- `hubert` 必需。
- `mode=split` 时 `text_encoder`、`flow`、`generator` 必需。
- `mode=combined` 时 `combined` 必需。
- 任一文件缺失 → 该音色不可选，UI 给出原因。
- `segment_frames` 必须为 16 的倍数（RMVPE 32 帧对齐），建议 64。
- 未写 `license` 的音色不允许打包进分发版。

### FR-3 实时转换链路

- 48 kHz / 单声道 / float32 采集，20 ms 帧。
- 转换器 hop 由模型决定：`segment_frames × 10 ms`（64 帧 → 640 ms）。
- 窗口：`context 0 + hop`，块间 10 ms 交叉淡化。
- 采样率转换：48k → 16k（模型输入）→ 模型输出率（40k/48k）→ 48k。
- 输出经峰值限幅，禁止削波。

### FR-4 转换质量

- F0 中值滤波，抑制八度跳变。
- 音高偏移 ±12 半音，按 `2^(n/12)` 缩放 F0。
- 无 RMVPE 时回退自相关 F0。
- 静音/清音段不允许出现持续噪声或爆音。
- 块边界无可闻“咔哒”。

### FR-5 延迟档位

| 档位 | hop | 端到端（估算） | 用途 |
|---|---|---|---|
| 直通 | 20 ms | 100–150 ms | 链路验证、兜底 |
| RVC 标准 | 640 ms | 1.0–2.0 s | 对讲、监听 |
| RVC 低延迟 | 320 ms | 0.7–1.3 s | 高端机，模型需支持 32 帧 |
| RVC 稳定 | 960 ms | 1.5–3.0 s | 中端机，优先不断音 |

RVC 实际 hop 以模型 `segment_frames` 为准，档位只是期望值；不匹配时以模型为准并提示。

### FR-6 对讲接入

- `AudioSink` 可替换：扬声器 / 网络 / 录音文件。
- 提供 `PushToTalkController`：按下开始采集，松开后先 flush 最后一个 hop 再停止（P3）。
- 半双工：播放对端音频时暂停本地上行（P3）。
- 音频格式：48 kHz float32 PCM，传输层自行转 16-bit/Opus。

### FR-7 失败与降级

| 场景 | 行为 |
|---|---|
| 推理异常 | 本块直通 + 淡入淡出，累计 `fallbackChunks` |
| 单块超时（> 2×hop） | 放弃该块，直通，记录 |
| 模型缺失/损坏 | 启动前拦截，音色置灰 |
| 采集溢出 | 丢最旧帧，`captureDrops++` |
| 播放欠载 | 补静音，`playbackUnderruns++` |
| 内存不足 | 捕获 OOM，停止并提示换小模型 |
| 蓝牙 SCO | 重采样兜底，提示音质下降 |

### FR-8 运行时与后端

- 默认 ONNX Runtime CPU + XNNPACK。
- 可选 NNAPI，失败自动回退 CPU。
- 模型加载在 IO 线程，首帧前 warm-up。
- 会话线程数默认 2–4，按设备核数限制。
- 后端、线程数写入设置，重启生效。

### FR-9 诊断

UI 展示：

- 状态、音色、hop、模型采样率。
- 单块推理耗时、平均耗时、RTF（推理耗时 / hop）。
- 丢帧、欠载、回退、已处理块数。
- 输入/输出 dBFS。
- 当前 ONNX 后端（CPU/XNNPACK/NNAPI）。

### FR-10 设置持久化

- 上次音色、音高、增益、噪声门、后端、延迟档位。
- 使用 DataStore/SharedPreferences，进程重启恢复。

### FR-11 隐私

- 无网络权限需求；模型导入可选本地 HTTP（后续）。
- 原始音频不落盘，仅内存环形缓冲。
- 日志不含音频样本。
- 一键清除模型与设置。

### FR-12 权限与生命周期

- `RECORD_AUDIO`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MICROPHONE`、`POST_NOTIFICATIONS`。
- 前台服务保活；锁屏继续。
- 来电/其他应用占用麦克风时暂停并提示。

---

## 4. 非功能需求

| 编号 | 指标 | 目标 |
|---|---|---|
| NFR-1 | 直通端到端延迟 | < 150 ms |
| NFR-2 | RVC 端到端延迟 | 1.0–2.0 s（旗舰），2–3 s（中端） |
| NFR-3 | RTF（推理/音频时长） | < 0.8（旗舰）；< 1.0 才能持续实时 |
| NFR-4 | 内存 | 峰值 < 2 GB；模型常驻 < 1.2 GB |
| NFR-5 | 启动（模型已缓存） | < 3 s 进入可转换状态 |
| NFR-6 | 稳定性 | 连续 30 min 无 OOM/ANR，掉帧率 < 1% |
| NFR-7 | 设备 | arm64-v8a，Android 8.0+，RAM ≥ 6 GB（RVC） |
| NFR-8 | 包体 | 不含模型 < 80 MB；模型独立导入 |
| NFR-9 | 功耗 | 连续转换 < 8 W（旗舰） |
| NFR-10 | 隐私 | 全离线，音频不出设备 |

### 性能预算（64 帧 / 640 ms 块）

| 阶段 | 旗舰目标 | 中端目标 |
|---|---|---|
| HuBERT | 200 ms | 400 ms |
| RMVPE | 80 ms | 150 ms |
| text_encoder | 40 ms | 80 ms |
| flow | 40 ms | 80 ms |
| generator | 120 ms | 250 ms |
| 前后处理 | 20 ms | 40 ms |
| **合计** | **500 ms（RTF 0.78）** | **1000 ms（RTF 1.56，不可持续）** |

> 中端机若 RTF > 1，自动切 960 ms 块或提示“仅支持离线/录音转换”。

---

## 5. 验收标准

### M1 直通（已完成）

- 戴耳机可听到自己声音，延迟 < 150 ms，无爆音。
- 前后台切换、锁屏继续运行。

### M2 RVC 拆分模型（本期）

1. 放入一套公开 RVC v2 拆分模型（hubert/rmvpe/text_encoder/flow/generator）。
2. 选择后模型已缓存 5 s 内进入“可转换”，UI 显示模型采样率和 hop。
3. 说话能听出目标音色，内容可懂。
4. 统计面板显示 RTF；旗舰机 RTF < 1。
5. 连续 10 min 无崩溃、无持续丢帧。
6. 缺失任一模型文件时，音色不可选并给出明确原因。

### M3 合并模型 + 对讲（下一期）

- 合并单模型可加载。
- `AudioSink` 接入网络传输，端到端对讲可用。
- PTT 松手不丢尾音。

---

## 6. 开发拆解

### 本期（M2）

| 编号 | 任务 | 产物 | 状态 |
|---|---|---|---|
| T1 | 需求与设计修订 | `REQUIREMENTS.md`、`DESIGN.md` | ✅ |
| T2 | 张量类型扩展（float/int64/int32/bool） | `core/inference/Tensor.kt`、`OnnxRuntimeBackend.kt` | ✅ |
| T3 | FFT + RMVPE Mel | `core/dsp/Fft.kt`、`RmvpeMel.kt` | ✅ |
| T4 | 模型协议扩展（拆分/合并/segment_frames） | `VoiceProfile.kt`、`VoiceProfileStore.kt` | ✅ |
| T5 | RVC 推理引擎 | `core/vc/rvc/RvcEngine.kt` | ✅ |
| T6 | 转换器接入引擎 | `core/vc/RvcConverter.kt` | ✅ |
| T7 | 管线队列/延迟参数适配 | `VoiceChangerPipeline.kt`、`ConversionConfig.kt` | ✅ |
| T8 | 测试与文档 | `FftTest.kt`、README、NOTICE | ✅ |

### 后续

| 编号 | 任务 |
|---|---|
| T9 | 合并模型真机验证 + 动态输出采样率 | 代码已实现，待真机 |
| T10 | 索引融合（离线、可选） |
| T11 | NNAPI/XNNPACK 实测与回退策略 |
| T12 | 对讲传输层（WebRTC/WebSocket）+ PTT |
| T13 | 模型导入 UI（文件夹/ZIP、断点） |
| T14 | QNN/HTP（仅骁龙旗舰） |
| T15 | 离线批量转换 |

---

## 7. 风险

| 风险 | 影响 | 对策 |
|---|---|---|
| 中端机 RTF > 1 | 无法实时 | 加长 hop、降级直通、明确设备门槛 |
| HuBERT 模型 360 MB | 内存/加载慢 | 常驻单例、int8、避免重复加载 |
| 模型输入形状不匹配 | 推理直接失败 | 加载时校验 inputs/shape，错误信息指向导出参数 |
| 固定 1024 帧导出 | 只能离线 | 文档强制要求可变/64 帧导出 |
| ONNX 算子不支持 NNAPI | 回退 CPU | 默认 XNNPACK，NNAPI 仅实验 |
| 声学反馈 | 啸叫 | 强制耳机/提示，AEC 依赖系统 |
| 模型许可 | 无法分发 | manifest 强制 license，模型不进 APK |
| 代码复用许可 | 合规风险 | 保留 Apache-2.0 NOTICE，标注来源 |

---

## 8. 依赖与参考

- ONNX Runtime Android 1.20+
- [MeowRVC](https://github.com/xiaoxiaoyu-miao/MeowRVC)（Apache-2.0）：RVC 推理链路参考
- [w-okada/voice-changer](https://github.com/w-okada/voice-changer)（MIT）：实时分块与拼接思路
- [RVC-Project](https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI)（MIT）：模型定义
- 基础模型：HuBERT / RMVPE ONNX（来自 RVC 生态，使用前确认各自许可）
