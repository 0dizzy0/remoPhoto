# 贡献指南

更新时间：2026-06-27

## 开始之前

1. 先阅读[项目状态](docs/project/项目状态.md)、[功能规格](docs/product/02_功能规格说明.md)和[测试用例](docs/testing/07_测试用例.md)。
2. 缺陷先确认是否已记录在[问题日志](docs/project/06_问题日志.md)。
3. 大范围功能、数据格式或协议变化应先通过 Issue 明确范围和兼容策略。

## 开发环境

- JDK 17
- Android SDK 35
- Android Studio 或 Gradle Wrapper

依赖优先下载到项目开发环境或开发盘。国内网络较慢时可使用项目已有镜像配置，但新增镜像前必须确认地址可达，且目标版本确实存在；官方 Google Maven 和 Maven Central 保留为兜底。

## 分支与提交

- 从最新主分支创建短生命周期功能分支。
- 一次提交只解决一个明确问题。
- 提交信息建议使用 `feat:`、`fix:`、`test:`、`docs:`、`refactor:` 等前缀。
- 不提交 `local.properties`、签名文件、APK/AAB、IDE 缓存、本地日志或包含隐私信息的测试数据。

## 质量要求

提交前至少执行与改动范围相符的检查。完整本地门禁为：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --console=plain
```

其中 `:app:lintDebug` 应输出 `No issues found`。`app/lint.xml` 中的局部例外必须写明原因，不得为通过门禁而扩大忽略范围。

涉及 UI、SAF、远程仓库、数据库迁移或导入导出时，还应按[测试用例](docs/testing/07_测试用例.md)执行对应真机用例，并使用[测试执行记录模板](docs/testing/测试执行记录模板.md)保存结果。

## 日志与可观测性

扫描、远程同步、服务自愈、数据库迁移和导入导出属于关键路径。修改这些流程时：

- 保留开始、阶段、完成、失败和耗时日志。
- 使用稳定 ID、数量和错误类型帮助定位问题。
- 避免逐项刷屏。
- Release 日志不得批量输出完整文件路径、相册名、设备地址或凭据。

## 文档同步

- 用户可见行为变化：更新功能规格和对应测试用例。
- 架构、数据库或协议变化：更新技术方案。
- 发布范围或门禁变化：更新 Alpha 发布计划。
- 关键缺陷修复：更新问题日志，写清验证证据和残留风险。
- 新增文档：更新[文档中心](docs/README.md)并验证相对链接。

所有文本文件使用 UTF-8 编码。
