放置音色目录：assets/models/<profileId>/

每个目录必须包含 manifest.json，格式见根目录 README.md。

拆分模型需要：
  hubert.onnx
  rmvpe.onnx（推荐）
  text_encoder.onnx
  flow.onnx
  generator.onnx

合并模型需要：
  hubert.onnx
  rmvpe.onnx（推荐）
  voice.onnx（manifest 的 combined 字段）

目录为空时，App 只显示内置的"直通（验证链路）"音色。
