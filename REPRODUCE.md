# 从零复现 / 换电脑迁移

这份文档说明：换一台电脑 clone 仓库后，哪些东西已经带上、哪些需要重新生成、哪些必须私下拷贝。

## 仓库里已经有的

- 全部源码（`app/`、`core/`）
- Gradle Wrapper（`gradlew`、`gradlew.bat`、`gradle/wrapper/`）
- 构建脚本：`tools/build-apk.ps1`
- 模型下载脚本：`tools/prepare-public-model.ps1`
- 文档：`README.md`、`DESIGN.md`、`REQUIREMENTS.md`、`dist/安装说明.md`
- 已签名的 APK：`dist/VoiceSim-0.2.1-arm64.apk`
- 公开模型的 `manifest.json`（仅配置，不含权重）

## 仓库里没有的（需要重新生成或私下拷贝）

| 缺失项 | 原因 | 如何补 |
|---|---|---|
| `local.properties` | 记录本机 SDK 路径 | Android Studio 打开项目自动生成；或 `sdk.dir=C:/Android/sdk` |
| `keystore/`、`keystore.properties` | 签名私钥和密码，不能公开 | 从旧电脑 U 盘/密码管理器拷贝；或重新生成（见下） |
| `dist/public-model/*.onnx` | 811 MB，超过 GitHub 单文件 100 MB 限制 | 运行 `tools/prepare-public-model.ps1` |
| `build/`、`.gradle/`、`.kotlin/` | 构建缓存 | 构建时自动生成 |
| Android SDK / JDK | 体积大且与机器相关 | 见下 |

## 新电脑步骤

### 1. 安装 JDK 17

```powershell
winget install --id Microsoft.OpenJDK.17 -e --source winget
```

或安装 Android Studio 自带的 JBR。

### 2. 安装 Android SDK

最简单：安装 Android Studio，打开项目自动同步。

或者用命令行工具：

1. 下载 `commandlinetools-win-*_latest.zip`。
2. 解压到 `C:\Android\sdk\cmdline-tools\latest`。
3. 执行：

```powershell
sdkmanager --sdk_root=C:\Android\sdk --licenses
sdkmanager --sdk_root=C:\Android\sdk "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

### 3. 克隆并构建

```powershell
git clone https://github.com/kingofluxury/123.git
cd 123
powershell -ExecutionPolicy Bypass -File .\tools\build-apk.ps1
```

Release 包：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\build-apk.ps1 -Release
```

> Release 签名依赖 `keystore.properties`。没有它时，Gradle 会产出未签名或 debug 签名的包；正式升级安装必须使用同一份 keystore。

### 4. 下载模型

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\prepare-public-model.ps1
```

默认输出 `dist/public-model/`。脚本会跳过已存在且大小完整的文件。

### 5. 安装到手机

```powershell
adb install -r .\dist\VoiceSim-0.2.1-arm64.apk
```

或直接把 APK 传到手机安装。

## 关于签名（重要）

仓库是公开的，**不能提交**：

- `keystore/voicesim.jks`
- `keystore.properties`
- 任何包含密码的文件

如果旧电脑的 keystore 丢失，新电脑可以重新生成，但新 APK 无法覆盖安装到已安装旧签名的手机上，必须卸载重装；后续升级也要一直用同一份新 keystore。

重新生成（仅本地）：

```powershell
keytool -genkeypair -v -keystore keystore\voicesim.jks -alias voicesim `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass 你的密码 -keypass 你的密码 `
  -dname "CN=VoiceSim,O=VoiceSim,C=CN"
```

然后创建 `keystore.properties`：

```properties
storeFile=keystore/voicesim.jks
storePassword=你的密码
keyAlias=voicesim
keyPassword=你的密码
```

## 模型与声音权利

`tools/prepare-public-model.ps1` 下载的是公开测试模型，仓库标注 MIT，但声音本身的授权不明确。仅用于本地技术验证；公开分发或商用前请确认权利，或换成自己训练/已授权的音色。

## 常见问题

- **`Your project path contains non-ASCII characters`**：项目路径不要带中文；或保留 `android.overridePathCheck=true`（仓库已配置）。
- **测试任务 `ClassNotFoundException`**：项目路径含中文时 Gradle 测试类加载会失败；把项目放到纯英文路径（例如 `C:\work\voicesim`）再跑 `gradlew :core:dsp:testDebugUnitTest`。
- **模型报 `inputs not recognized`**：导出脚本的节点名不同，需要在 `RvcEngine` 的 `runCombined` / `runSplit` 里补映射。
- **RTF > 1**：手机算力不足，换 960 ms 分块或改用更小模型。
