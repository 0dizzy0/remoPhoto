# remoPhoto

remoPhoto 是一款面向 Android 的本地与局域网相册管理应用。它以用户授权的文件目录作为图片仓库，按子目录建立相册索引，并提供分页、排序、分类、全屏浏览和可信局域网设备间共享能力。

本项目使用 AI 辅助完成需求整理、方案分析、编码、测试、文档和发版工作；项目维护者负责范围决策、风险接受、真机环境和最终发布授权。AI 生成或修改的内容仍需通过 Git 审阅、构建、Lint、自动化测试和真机回归。~~这份 README 也有 AI 帮忙。~~

> 当前公开版本为 [`0.1.0`](https://github.com/0dizzy0/remoPhoto/releases/tag/v0.1.0)，已通过 GitHub Releases 发布。APK SHA-256 为 `aa3a392eb1085008be9a16260419b80b18fd3326683eb179f399300d78c56eb1`；本地门禁、双机远程、备份恢复、Release 日志隐私、`beta.1 -> 0.1.0` 数据保留、CI 和线上附件 hash/签名/版本复验均已通过。

## 核心能力

- 通过 SAF 添加多个本地图片仓库并持久化授权。
- 扫描 PNG、JPG/JPEG、GIF、WebP、BMP，按目录生成层级相册。
- 相册分页、网格/列表切换、排序和名称模糊定位；翻页回到顶部，从图片页返回时保留原页码，点击省略号可自由跳页。
- 图片网格与全屏浏览，支持手势、音量键、鼠标滚轮和自动播放。
- 创建分类并关联本地或已连接的远程相册。
- 通过 HTTP + mDNS 在同一可信局域网内发现和浏览其他 Android 设备。
- 导入导出数据库与设置，不导出远程凭据。
- 为扫描、同步、服务和备份流程保留可观测日志。

## 技术栈

- Kotlin、Jetpack Compose、Material 3
- MVVM + Repository + UseCase
- Room、DataStore、WorkManager
- Coil、NanoHTTPd、JmDNS
- minSdk 29，targetSdk 35，JDK 17

## 安装与升级

公开安装包只通过 [GitHub Releases](https://github.com/0dizzy0/remoPhoto/releases) 提供。下载与版本号一致的 APK 和 `.sha256` 文件后，建议先在 PowerShell 中校验：

```powershell
Get-FileHash .\remoPhoto-<version>.apk -Algorithm SHA256
Get-Content .\remoPhoto-<version>.apk.sha256
```

两个值应完全一致。首次安装需要允许当前文件管理器或浏览器“安装未知应用”；安装完成后应关闭该临时授权。

- 从 `0.1.0-alpha.3` 或更新的公开签名版本覆盖安装时，数据库和设置应保留；升级前仍建议使用应用内“导出数据库”创建备份。
- 更早的内部 Alpha 2 使用不同签名，不能直接覆盖。需要先在旧版本导出备份，再卸载旧包、安装公开版本并导入。
- Android 系统云备份不会复制本应用数据库和设置，迁移设备时必须使用应用内导入导出。
- 不要从第三方 APK 站点下载安装包。发现签名或 SHA-256 不一致时应立即停止安装。

## 本地构建

前置条件：

- JDK 17
- Android SDK 35
- 可用的 Android SDK 路径配置

Windows PowerShell：

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
```

基础质量检查：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --console=plain
```

发布候选 APK 的基础 Smoke 证据可用脚本收集：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-smoke.ps1 `
  -Serial "<adb-serial>" `
  -ApkPath "app\build\outputs\apk\release\app-release.apk" `
  -ExpectedVersionName "<versionName>" `
  -ExpectedVersionCode <versionCode>
```

脚本会校验 APK SHA-256、签名、安装、版本、冷启动、UI dump、截图、Crash buffer、进程日志、应用文件日志、基础 Release 日志隐私扫描和内存快照，证据默认写入 `.cache/qa/smoke/`。如需同时执行 JVM、Lint 和 Release 构建，可增加 `-BuildRelease`。

当前 `0.1.0` 已发布，APK SHA-256、验证证据和残留风险见[项目状态](docs/project/项目状态.md)。

日常 UI 小改动可在单台已授权真机上运行无人值守 smoke（只安装独立的 Debug 包，不覆盖公开版数据）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-ui-smoke.ps1
```

脚本自动构建、安装并在真机上运行确定性的分页状态断言，最后直接输出 `PASS` 或带原因的 `FAIL`，证据写入 `.cache/qa/ui-smoke/`；截图仅供人工复核，不参与结果判定。

### 本地 Release 签名

首次配置可执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-release-signing.ps1 `
  -KeytoolPath "<JDK目录>\bin\keytool.exe"
```

脚本会在项目目录的 `.signing/` 中生成 PKCS12 keystore 和本地属性文件，不会输出密码，也不会覆盖已有密钥。该目录已被 Git 忽略，但必须离线备份；丢失密钥后将无法为同一应用签署后续升级。

连接可移动存储后，可执行以下命令创建带文件名加密的 7z 备份并自动校验；密码由 7-Zip 交互读取，不会进入命令行或脚本日志：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\backup-release-signing.ps1 `
  -DestinationDirectory "<可移动存储目录>"
```

部分外接 SSD 会被 Windows 识别为固定磁盘，此时确认目标确为独立外接介质后，可显式增加 `-AllowFixedDrive`。备份密码必须保存在本机之外；生成的归档和 `.sha256` 文件不得提交仓库。

CI 或其他开发环境也可以使用 `REMOPHOTO_RELEASE_STORE_FILE`、`REMOPHOTO_RELEASE_STORE_PASSWORD`、`REMOPHOTO_RELEASE_KEY_ALIAS`、`REMOPHOTO_RELEASE_KEY_PASSWORD` 环境变量。

## 文档

- [文档中心](docs/README.md)
- [AI 辅助开发说明](docs/project/AI辅助开发说明.md)
- [项目状态](docs/project/项目状态.md)
- [功能规格](docs/product/02_功能规格说明.md)
- [技术方案](docs/architecture/03_技术方案概要.md)
- [测试用例](docs/testing/07_测试用例.md)
- [Alpha 发布计划](docs/releases/08_首个AlphaRelease计划.md)
- [Beta 与正式版发布计划](docs/releases/09_Beta与正式版Release计划.md)
- [贡献指南](CONTRIBUTING.md)
- [安全说明](SECURITY.md)

## 项目目录

```text
.
├── app/               # Android 应用模块
├── docs/              # 产品、架构、项目、测试和发布文档
├── scripts/           # 本地工程维护脚本
├── gradle/wrapper/    # Gradle Wrapper
├── .github/           # GitHub Issue 与 Pull Request 模板
├── README.md
├── CONTRIBUTING.md
├── SECURITY.md
├── CHANGELOG.md
└── LICENSE
```

`.idea/`、`.gradle/`、`.kotlin/`、`local.properties`、构建产物、签名文件和本地 AI 工具目录不会提交到仓库。

## 安全边界

远程仓库当前使用明文 HTTP，仅用于受信任的同一局域网。不要在公共 Wi-Fi 或公网暴露远程服务。提交日志前请移除完整文件路径、相册名、设备 IP 等敏感信息。

系统云备份和设备迁移不会自动复制应用数据库、设置或连接元信息；数据迁移应使用应用内导入导出。Release 构建关闭 Logcat 和 DEBUG 文件日志，并统一脱敏 URI、路径、IP 与用户自定义名称。

## AI 辅助开发

- 主要 AI 开发代理：OpenAI Codex。
- 仓库级代理约束：`AGENTS.md`；`CLAUDE.md` 作为 Claude 兼容入口并复用同一约束。
- 当前工作区 Skills：`mobile-android-design`、`find-skills`；本次 Alpha 发版还使用了 Codex GitHub 插件提供的 `github:github` 工作流 Skill。
- Claude 本地工作区启用了 `chrisbanes-skills@chrisbanes-skills` 插件；该本地配置不随仓库发布。
- 当前项目没有配置项目级 MCP Server，本次 Alpha 发版也没有通过 MCP 访问项目或用户数据。
- Gradle、ADB、Git、GitHub CLI、`apksigner`、`aapt2`、WebSearch/WebFetch 属于构建、验证或检索工具，不等同于 Skill 或 MCP。
- AI 配置、Skills 和缓存目录默认不进入版本控制，也不会打包进 APK；remoPhoto 本身不包含运行时 AI 功能，不会把用户图片发送给 AI 服务。

完整清单、来源和边界见 [AI 辅助开发说明](docs/project/AI辅助开发说明.md)。

## 开源许可

本项目采用 [MIT License](LICENSE)。
