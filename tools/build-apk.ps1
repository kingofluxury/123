param(
    [switch]$Release,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

function Find-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }
    $patterns = @(
        "C:\Program Files\Microsoft\jdk-17*",
        "C:\Program Files\Eclipse Adoptium\jdk-17*",
        "C:\Program Files\Java\jdk-17*"
    )
    foreach ($pattern in $patterns) {
        $dir = Get-ChildItem $pattern -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($dir -and (Test-Path (Join-Path $dir.FullName "bin\java.exe"))) {
            return $dir.FullName
        }
    }
    return $null
}

$javaHome = Find-JavaHome
if (-not $javaHome) {
    throw "未找到 JDK 17。安装命令：winget install --id Microsoft.OpenJDK.17 -e --source winget"
}
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"

$localProperties = Join-Path $root "local.properties"
if (-not (Test-Path $localProperties)) {
    if ($env:ANDROID_HOME) {
        $sdk = $env:ANDROID_HOME -replace '\\', '/'
        "sdk.dir=$sdk" | Set-Content $localProperties -Encoding ASCII
        Write-Host "已生成 local.properties：sdk.dir=$sdk"
    } else {
        throw "缺少 local.properties 或 ANDROID_HOME。请用 Android Studio 打开项目，或手动创建 local.properties 写入 sdk.dir=C:/Android/sdk"
    }
}

$task = if ($Release) { ":app:assembleRelease" } else { ":app:assembleDebug" }
$arguments = @($task, "--console=plain")
if ($Clean) {
    $arguments = @("clean") + $arguments
}

Write-Host "JAVA_HOME=$javaHome"
Write-Host "执行：gradlew $($arguments -join ' ')"
& (Join-Path $root "gradlew.bat") @arguments
if ($LASTEXITCODE -ne 0) {
    throw "构建失败，退出码 $LASTEXITCODE"
}

if ($Release) {
    $dir = Join-Path $root "app\build\outputs\apk\release"
} else {
    $dir = Join-Path $root "app\build\outputs\apk\debug"
}
Get-ChildItem $dir -Filter *.apk -ErrorAction SilentlyContinue |
    Select-Object Name, @{n = "MB"; e = { [math]::Round($_.Length / 1MB, 2) } }, FullName
