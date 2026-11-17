# 下载一套可直接导入 VoiceSim 的公开 RVC ONNX 模型
#
# 用法（Windows PowerShell）：
#   powershell -ExecutionPolicy Bypass -File .\tools\prepare-public-model.ps1
#
# 生成的目录：dist\public-model\
#   vec-768-layer-12.onnx   ContentVec / HuBERT，约 377 MB
#   rmvpe.onnx              F0 提取，约 362 MB
#   woman_1.onnx            合并 RVC 模型，约 111 MB
#   manifest.json
#
# 然后把整个 public-model 目录传到手机，在 App 里点“导入音色目录”。
#
# 许可提示：模型仓库标注 MIT，但声音本身的肖像/声音权利并未明确；
# 仅供本地技术测试，不要用于商业发布或冒充他人。

param(
    [string]$OutDir = (Join-Path $PSScriptRoot "..\dist\public-model"),
    [ValidateSet("woman_1", "woman_2", "woman_3", "woman_4")]
    [string]$Voice = "woman_1"
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$base = "https://huggingface.co/ozada/onnx_rvc/resolve/main"
$files = [ordered]@{
    "vec-768-layer-12.onnx" = "$base/vec-768-layer-12.onnx?download=true"
    "rmvpe.onnx"            = "$base/rmvpe.onnx?download=true"
    "$Voice.onnx"           = "$base/$Voice.onnx?download=true"
}

foreach ($name in $files.Keys) {
    $target = Join-Path $OutDir $name
    Write-Host "下载 $name ..."
    & curl.exe -L --retry 10 --retry-delay 3 -C - -o "$target" $files[$name]
    if ($LASTEXITCODE -ne 0) {
        throw "下载失败：$name"
    }
    $size = (Get-Item $target).Length / 1MB
    Write-Host ("  完成：{0:N1} MB" -f $size)
}

$manifest = [ordered]@{
    id             = "public-demo"
    name           = "公开示例（$Voice）"
    description    = "ContentVec + RMVPE + 合并 RVC ONNX，用于链路验证"
    version        = "2.0"
    sample_rate    = 40000
    segment_frames = 64
    f0             = $true
    speaker_id     = 0
    mode           = "combined"
    files          = [ordered]@{
        hubert = "vec-768-layer-12.onnx"
        rmvpe  = "rmvpe.onnx"
    }
    combined       = "$Voice.onnx"
    license        = "模型仓库标注 MIT；声音权利未确认，仅供本地测试"
    source         = "https://huggingface.co/ozada/onnx_rvc"
}

$json = $manifest | ConvertTo-Json -Depth 5
[System.IO.File]::WriteAllText(
    (Join-Path $OutDir "manifest.json"),
    $json,
    (New-Object System.Text.UTF8Encoding($false))
)

Write-Host ""
Write-Host "完成：$OutDir"
Write-Host "下一步：把整个目录传到手机，在 VoiceSim 里点“导入音色目录（含 manifest.json）”。"
