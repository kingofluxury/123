# Third-party notices

本项目包含或参考了以下开源项目。分发时请保留本文件与各项目许可证。

## MeowRVC

- 仓库：https://github.com/xiaoxiaoyu-miao/MeowRVC
- 许可证：Apache License 2.0
- 使用范围：`core/vc/rvc/RvcEngine.kt`、`core/dsp/RmvpeMel.kt`、`core/dsp/Fft.kt` 的 RVC 推理链路、RMVPE Mel 前端与 FFT 实现基于该项目重构/移植。

## ONNX Runtime

- 仓库：https://github.com/microsoft/onnxruntime
- 许可证：MIT
- 使用方式：Android AAR（`com.microsoft.onnxruntime:onnxruntime-android`）。

## RVC（Retrieval-based Voice Conversion）

- 仓库：https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI
- 许可证：MIT
- 使用范围：模型结构、F0/Mel 参数与推理流程参考。

## w-okada/voice-changer

- 仓库：https://github.com/w-okada/voice-changer
- 许可证：MIT
- 使用范围：实时分块、上下文与拼接思路参考（未直接复制代码）。

## 模型权重

HuBERT、RMVPE、RVC 音色模型不随本仓库分发。使用前请确认各自许可证；`manifest.json` 的 `license` 字段为必填项。
