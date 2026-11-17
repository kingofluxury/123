# VoiceSim

Android 端实时音色转换（Voice Conversion）框架：麦克风 → 端侧 RVC 推理 → 目标音色 PCM，用于 App 内语音对讲。

- 详细需求：[REQUIREMENTS.md](REQUIREMENTS.md)
- 架构与取舍：[DESIGN.md](DESIGN.md)

## 安装包

已构建并签名：

- `dist/VoiceSim-0.2.1-arm64.apk`（约 23.9 MB，arm64-v8a，Android 8.0+）
- SHA-256：`1bbded988cd02317235afd07563a2f7875e6ac7abb1df4e1bf68126bd62dc02d`
- 安装与模型导入：见 `dist/安装说明.md`
- 已附带公开测试模型：`dist/public-model/`（约 811 MB）
- 一键重新下载：`tools/prepare-public-model.ps1`

APK 不含模型权重，首次使用需通过“导入音色目录”导入一套 RVC v2 模型。

---

## 当前状态

| 能力 | 状态 |
|---|---|
| 48 kHz 采集/播放、三线程实时管线、有界队列 | ✅ |
| 前台服务、锁屏/后台运行、权限 | ✅ |
| 直通模式（测延迟、兜底） | ✅ |
| 拆分 RVC v2：HuBERT + RMVPE + text_encoder + flow + generator | ✅ |
| 合并 RVC 模型（phone/phone_lengths/pitch/nsff0/sid） | ✅ |
| F0 中值滤波、音高 ±12 半音、块间交叉淡化、峰值限幅 | ✅ |
| RTF/丢帧/欠载/回退统计 | ✅ |
| 无 RMVPE 时自相关 F0 兜底 | ✅ |
| 索引（index）检索融合 | 🚧 未做（实时太慢） |
| NNAPI/XNNPACK 实测调优 | 🚧 P3 |
| 对讲传输（WebRTC/WebSocket）+ PTT | 🚧 P5 |

> 手机上 RVC 的真实水平是 **1–2 秒延迟**，不是毫秒级。中端机 RTF 可能 > 1，只能离线或降级。详见需求文档的性能预算。
>
> 说明：RVC 推理链路按 MeowRVC 的导出接口实现，尚未在真机上跑过。首次接入请先用一个已知可用的 RVC v2 ONNX 模型验证输入名/形状；不同导出脚本的节点名可能需要在 `RvcEngine` 里补映射。

---

## 模块

```
app                  Compose 界面、前台服务、VoiceChangerController
core/audio           AudioRecord/AudioTrack、FrameQueue、FloatFifo、音频线程
core/dsp             重采样、Biquad、VAD、YIN、FFT、RMVPE Mel、限幅
core/inference       类型化 Tensor、ModelSession、ONNX Runtime 后端
core/vc              VoiceConverter、RvcEngine、模型清单、实时管线
```

依赖方向：`app → core:vc → core:inference / core:dsp / core:audio`。

---

## 构建

环境：

- Android Studio Ladybug 或更新（或 JDK 17 + Android SDK 35 + Gradle 8.9+）
- 手机：arm64-v8a，Android 8.0+，RVC 建议 8 GB 内存

```bash
./gradlew :app:assembleDebug
```

> 本仓库当前没有 `gradlew`，用 Android Studio 打开根目录会自动同步；也可以在装了 Gradle 的机器上执行 `gradle wrapper` 生成。

首次运行：

1. 允许录音权限。
2. 默认音色是“直通（验证链路）”，点开始即可验证采集/播放。
3. **戴耳机**，否则扬声器会被麦克风再次采集，产生啸叫。

---

## 放入 RVC 模型

目录：

```
app/src/main/assets/models/<id>/
├── manifest.json
├── hubert.onnx
├── rmvpe.onnx
├── text_encoder.onnx
├── flow.onnx
├── generator.onnx
├── config.json          # 可选，target_sr
└── speaker.bin          # 可选，256 维 float32
```

`manifest.json`：

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
  "license": "CC-BY-4.0",
  "source": "https://example.com/model"
}
```

合并模型把 `mode` 改成 `combined`，加 `"combined": "voice.onnx"`，去掉 split 三件套。

首次启动会把 `assets/models` 复制到 `filesDir/models`，之后可以直接替换文件或用 adb push：

```bash
adb push demo /sdcard/Android/data/com.voicesim/files/models/
```

### 模型从哪来

- HuBERT（`hubert.onnx`，约 360 MB）与 RMVPE（`rmvpe.onnx`，约 180 MB）来自 RVC 生态，不同导出脚本形状可能不同。
- 音色模型：用 RVC v2 的 `.pth` 导出 ONNX。**必须导出可变长度或 64 帧固定窗口**；如果导出成 1024 帧固定输入，单次要吃 10 秒音频，只能离线用（atri-vc 就卡在这里）。
- 模型与音色版权归各自权利人，manifest 必须写 `license`，不要打包来源不明的模型。

---

## 参数与延迟

| 参数 | 位置 | 说明 |
|---|---|---|
| 分块 | 模型 `segment_frames` | 64 帧 = 640 ms；32 帧 = 320 ms |
| 音高 | 界面滑杆 | ±12 半音，F0 乘 `2^(n/12)` |
| 交叉淡化 | `ConversionConfig.crossfadeMs` | 默认 10 ms |
| 采集队列 | `PipelineConfig.captureQueueFrames` | 默认 64 帧（1.28 s） |
| 播放 FIFO | `PipelineConfig.playbackBufferMs` | 默认 2 s |

界面统计里的 **RTF = 单块推理耗时 / 分块时长**：

- RTF < 1：能持续实时，延迟稳定。
- RTF > 1：会持续欠载/丢帧，只能加长分块或换更小模型。

---

## 与对讲链路对接

`AudioSink` 就是一个 PCM 写出接口：

- 本机监听：`AndroidAudioTrackSink`
- 对讲上行：实现 `NetworkAudioSink`，在 `write()` 里把 float32 PCM 转 16-bit/Opus 交给 WebRTC/WebSocket
- 录音存档：实现 `FileAudioSink`

采集侧同理，`AudioSource` 可以是麦克风，也可以是网络下行。管线不关心数据来源。

---

## 已知限制

- **不是系统级虚拟麦克风**：不能直接喂给微信/游戏，需要 root 或系统签名。
- **不支持 index**：`index_rate` 参数保留但未生效。
- **线性重采样无带限**：48k → 16k 有混叠，P3 换多相 FIR。
- **模型体积大**：HuBERT + RMVPE + 生成器通常 600 MB 以上，首次加载需要几秒。
- **NNAPI 不可靠**：默认 XNNPACK；部分机型 RVC 算子会回退 CPU。
- **中端机可能跑不动**：RTF > 1 时建议只做离线/录音转换。

---

## 测试

```bash
./gradlew :core:dsp:test
```

覆盖 FFT 冲激响应、RMVPE Mel 形状/数值、线性重采样长度与常量输入。

---

## 第三方

见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。RVC 推理链路参考并移植自 MeowRVC（Apache-2.0）。
