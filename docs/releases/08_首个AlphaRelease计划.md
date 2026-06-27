# remoPhoto 首个 Alpha Release 计划

更新时间：2026-06-27

## 1. 发布目标

本次目标不是发布“功能完全稳定版”，而是发布个人开源项目的首个可试用 Alpha 版本：

- 能让作者和少量早期用户从源码或安装包体验核心功能。
- 能收集真实设备、真实图片仓库、局域网远程仓库场景下的问题。
- 能证明项目具备基本工程可维护性：可构建、可签名、可回归、可定位日志、可回滚。
- 明确标注已知限制，避免把尚未验证充分的功能包装成稳定承诺。

建议版本名：`1.0.0-alpha.3` 或 `0.1.0-alpha.1`。

如果希望保留未来正式 `1.0.0` 的语义，建议采用 `0.1.0-alpha.1`。如果当前项目文档已经围绕 `1.0.0-alpha.x` 演进，则继续使用 `1.0.0-alpha.3` 也可以。

## 2. 当前状态摘要

| 项目 | 当前状态 | Alpha 发布判断 |
| --- | --- | --- |
| Debug 构建 | `:app:assembleDebug` 通过 | 达标 |
| Release 构建 | `:app:assembleRelease` 通过 | 基本达标 |
| Release 产物 | 当前为 `app-release-unsigned.apk` | 阻塞，需要签名 |
| JVM 单元测试 | 3 个用例通过 | 可作为 alpha 起点，但覆盖不足 |
| Android 仪器/Compose 测试 | 暂无 | 不阻塞 alpha，但需要列入后续 |
| Lint | 2026-06-27：`No issues found` | 达标；含 2 项局部、带原因的例外 |
| Room 数据库 | 当前版本 v4，迁移链 1->2->3->4 存在 | 需要补 schema 导出和最小迁移验证 |
| 远程仓库 | 已有多轮真机验证和大仓库优化 | 可纳入 alpha，但需列已知限制 |
| 扫描大仓库 | 已引入 WorkManager、Spool、事务切换 | 可纳入 alpha，需保守说明 |
| 发布文档 | 已有计划、检查清单和测试记录模板 | 模板达标，发布前仍需填写实际结果 |
| Git 状态 | 当前工作区存在未提交改动和未跟踪目录 | 阻塞，需要整理提交范围 |
| GitHub 基础文档 | README、贡献、安全、更新日志和模板已补齐 | 基本达标 |
| 开源许可证 | 尚未选择 | 公开分发源码前阻塞 |

## 3. Alpha 范围

### 3.1 本次应包含的功能

首个 Alpha 建议包含以下能力：

- 本地图片仓库添加、扫描、相册列表浏览。
- 相册分页、列表/网格切换、相册排序、相册名称模糊定位。
- 图片网格浏览、全屏查看、基础手势、音量键/滚轮翻页。
- 分类创建、编辑、删除、本地/已连接远程相册关联。
- 设置页：主题、排序、分页数量、播放间隔、远程服务、缓存统计、导入导出入口。
- 局域网 HTTP 远程仓库服务端、mDNS 发现、远程仓库添加和同步。
- 远程相册缓存、远程封面、远程离线状态下的基本表现。
- 数据库导入导出，包含远程连接元信息但不包含凭据。
- 关键路径 AppLogger 日志。

### 3.2 本次明确不承诺的功能

以下内容不应作为首个 Alpha 的发布承诺：

- PC 端 SMB 仓库支持。
- 多用户权限管理。
- 视频播放。
- 跨公网远程访问或 HTTPS/TLS 安全传输。
- 扫描任务被系统杀进程后从目录队列级检查点精确恢复。
- 对所有 Android 设备厂商后台限制的完整兼容。
- Play Store 上架质量标准。

## 4. Alpha 阻塞项

这些任务建议完成后再发布安装包。

### A1. Lint 门禁（已完成）

历史 Compose error 在本轮复核时已不再复现。本轮进一步处理剩余 27 条 warning：

- 25 条通过代码或资源修正。
- `OldTargetApi`：当前 Alpha 固定 targetSdk 35，API 36/AGP 升级留作独立兼容任务。
- `ObsoleteSdkInt`：AAPT 要求自适应图标保留在 `mipmap-anydpi-v26`，仅对该路径例外。

验收标准：

```powershell
.\gradlew.bat :app:lintDebug --console=plain
```

2026-06-27 验证结果为 `BUILD SUCCESSFUL` 和 `No issues found`。后续代码变更不得新增未处理告警，`app/lint.xml` 的例外不得无理由扩大。

### A2. 配置 Release 签名

当前 release 产物是未签名 APK：

- `app/build/outputs/apk/release/app-release-unsigned.apk`

需要增加本地签名配置。建议不要把 keystore、密码、私钥提交到仓库。可以使用 `local.properties` 或环境变量读取。

验收标准：

- 能生成 `app-release.apk` 或等价 signed APK/AAB。
- 安装到真机成功。
- `adb shell dumpsys package com.remophoto` 能看到版本号符合本次发布。

建议命令：

```powershell
.\gradlew.bat :app:assembleRelease --console=plain
```

### A3. 确定 Alpha 版本号

当前配置：

- `versionCode = 2`
- `versionName = "1.0.0-alpha.2"`

建议发布前改为：

- `versionCode = 3`
- `versionName = "1.0.0-alpha.3"`

或改为：

- `versionCode = 1`
- `versionName = "0.1.0-alpha.1"`

如果已有用户安装过 `versionCode = 2`，则必须递增到 `3` 或更高。

验收标准：

- APK 元数据中的 versionCode/versionName 与发布说明一致。
- Git tag 与 versionName 一致，例如 `v1.0.0-alpha.3`。

### A4. 整理 Git 工作区

发布前需要把代码改动、文档改动、生成目录分开处理。

当前需要重点排查：

- `.agents/`
- `.cache/`
- `.claude/`
- `.codex/`
- `skills-lock.json`
- `AGENTS.md`
- `CLAUDE.md`
- launcher icon 相关资源

其中本地 AI 工具目录和 `skills-lock.json` 已由 `.gitignore` 排除；`docs/design/` 是明确的项目设计文档目录，可以纳入版本控制。`AGENTS.md`、`CLAUDE.md` 是否公开应按仓库协作策略逐项确认。

验收标准：

```powershell
git status --short
```

结果中只应存在本次 release 明确要提交的文件。发布 tag 前应为干净工作区。

### A5. Room Schema 导出

当前 `exportSchema = true`，但构建时提示未配置 schema 导出目录。

建议：

- 配置 Room schemaLocation，例如 `app/schemas`。
- 将 schema JSON 纳入版本控制。
- 后续 migration 测试基于 schema 做回归。

Alpha 前最低验收：

- release 构建不再出现 Room schemaLocation 警告，或在发布说明中明确说明暂未处理。
- 优先建议处理，避免后续数据库迁移不可追踪。

### A6. 最小发布回归

Alpha 前至少完成一轮手工 Smoke Test，范围以[核心功能测试用例](../testing/07_测试用例.md)为准，结果使用[测试执行记录模板](../testing/测试执行记录模板.md)归档并链接到 Release Notes。

最低覆盖：

- 冷启动。
- 覆盖安装。
- 添加本地仓库。
- 小仓库扫描。
- 大仓库或模拟大仓库进入相册列表。
- 相册排序和模糊定位。
- 打开相册和全屏图片。
- 设置页切回相册列表。
- 开启远程服务。
- 添加远程仓库。
- 远程相册列表分页。
- 断开 Wi-Fi 后分类中远程相册隐藏。
- 恢复网络后远程仓库重新连接。
- 数据库导出。
- 数据库导入或至少导入文件格式校验。
- crash buffer 无新增 `FATAL EXCEPTION`。

执行结果不得直接堆叠到测试设计正文，应复制[测试执行记录模板](../testing/测试执行记录模板.md)形成独立记录。

### A7. 确定开源许可证

项目目标是个人开源发布，但当前仓库没有 `LICENSE`。公开可见不等于授予复制、修改或分发权。

验收标准：

- 根据预期授权边界选择许可证。
- 仓库根目录存在完整 `LICENSE`。
- README 和 GitHub Release 的许可证表述一致。
- 第三方依赖许可证与选定分发方式不存在明显冲突。

## 5. Alpha 强烈建议项

这些不一定阻塞 alpha，但完成后可以显著降低早期试用风险。

### B1. 发布日志降噪和脱敏

当前日志对调试非常有帮助，但 release 版本应减少：

- 文件绝对路径。
- 设备 IP、host、port 的高频重复输出。
- 相册名、仓库名的大量逐项输出。
- DEBUG 级别日志。

建议策略：

- Debug 构建：保留 `DEBUG`。
- Release 构建：默认 `INFO` 或 `WARN`。
- 大仓库场景只输出汇总日志，避免成千上万条逐相册日志。
- 对路径类信息保留末尾文件名或 hash，避免完整路径批量泄漏。

验收标准：

- Alpha release 日志仍能定位扫描、同步、导入导出、远程连接问题。
- 不在正常浏览流程中持续刷大量 DEBUG 日志。

### B2. Manifest 发布策略复核

当前 manifest 中存在：

- `android:allowBackup="true"`
- `android:usesCleartextTraffic="true"`

建议：

- `allowBackup`：个人开源 alpha 可以保留，但需要确认是否会备份数据库、远程连接元信息、用户路径信息。
- `usesCleartextTraffic`：局域网 HTTP 远程仓库需要明文 HTTP，可保留；但建议在 README 和 release notes 中声明仅用于可信局域网。
- 后续可以增加 network security config，限制或说明明文流量使用范围。

验收标准：

- 发布说明中包含远程访问安全提示。
- 不宣传为安全的公网访问方案。

### B3. 自动化测试补充

Alpha 前最低可接受：现有 3 个 JVM 测试通过，并完成手工 smoke test。

Alpha 后第一优先级建议补：

- Room v3->v4 migration 测试。
- 相册分页边界测试。
- 相册排序稳定性测试。
- 远程 API DTO 兼容测试。
- 缓存 TTL 策略测试。
- 导入导出 manifest/version 校验测试。

建议新增依赖时优先使用官方源和已有镜像配置，确认版本在镜像仓库存在。

### B4. Release Notes

当前已建立 `CHANGELOG.md` 骨架。发布前仍需在 Changelog 和 GitHub Release 中写明：

- 本版本是 Alpha。
- 已验证设备和 Android 版本。
- 核心功能列表。
- 已知限制。
- 升级/覆盖安装注意事项。
- 日志和问题反馈方式。

首个 Alpha 的已知限制建议包含：

- SMB 未支持。
- 远程仓库仅面向可信局域网。
- 大仓库扫描仍可能受设备厂商后台限制影响。
- 进程被系统杀死后，扫描任务可能重新遍历。
- 远程服务需要两台设备处于同一局域网。
- 暂无完整 UI 自动化测试覆盖。

## 6. 可推迟到 Beta 的事项

以下事项不建议阻塞首个 Alpha：

- 全量 Compose UI 自动化测试。
- 完整 adb 自动回归脚本。
- SMB 支持。
- HTTPS/TLS 或令牌认证。
- 多语言资源整理。
- Play Store 图标、截图、隐私政策完整材料。
- 性能指标硬门槛，例如大仓库必须低于固定秒数。
- 目录队列级扫描检查点。

## 7. 发布流程建议

### 7.1 准备阶段

1. 修复 alpha 阻塞项。
2. 更新版本号。
3. 配置签名。
4. 更新文档：
   - [项目状态](../project/项目状态.md)
   - [核心功能测试用例](../testing/07_测试用例.md)
   - [更新日志](../../CHANGELOG.md)和 Release Notes
5. 整理 Git 工作区。

### 7.2 本地门禁

建议发布前执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --console=plain
```

如果后续补了 instrumentation test，再增加：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

### 7.3 真机 Smoke Test

建议至少使用 1 台主力手机完成本地功能，2 台同局域网设备完成远程仓库。

建议记录：

- 测试日期。
- commit hash。
- APK versionCode/versionName。
- 设备型号。
- Android 版本。
- 是否覆盖安装。
- 本地仓库规模。
- 远程仓库规模。
- crash buffer 结果。
- 关键 AppLogger 摘要。

### 7.4 打 Tag 和发布

建议流程：

```powershell
git status --short
git add <明确的代码和文档文件>
git commit -m "Prepare first alpha release"
git tag v1.0.0-alpha.3
```

如果推到 GitHub：

```powershell
git push origin <branch>
git push origin v1.0.0-alpha.3
```

发布附件建议包含：

- signed APK。
- SHA-256 校验值。
- release notes。

生成 SHA-256：

```powershell
Get-FileHash app\build\outputs\apk\release\<signed-apk-name>.apk -Algorithm SHA256
```

## 8. Alpha 验收清单

发布前逐项确认：

- [ ] `versionCode` 已递增。
- [ ] `versionName` 已更新为本次 alpha。
- [ ] Release 签名配置可用。
- [ ] 生成 signed release APK/AAB。
- [ ] `:app:testDebugUnitTest` 通过。
- [ ] `:app:lintDebug` 通过并输出 `No issues found`。
- [ ] `:app:assembleRelease` 通过。
- [ ] Room schema 导出问题已处理或明确记录。
- [ ] 真机覆盖安装成功。
- [ ] 冷启动无崩溃。
- [ ] 本地仓库扫描可用。
- [ ] 大仓库列表和分页可用。
- [ ] 相册查找可用。
- [ ] 全屏浏览可用。
- [ ] 设置页切换导航可用。
- [ ] 远程服务可启动。
- [ ] 远程仓库可添加。
- [ ] 远程仓库分页可用。
- [ ] 网络断开和恢复表现符合预期。
- [ ] 数据库导出可用。
- [ ] 导入流程至少完成基本校验。
- [ ] crash buffer 无新增崩溃。
- [ ] AppLogger 没有正常流程下的大量隐私路径泄漏。
- [ ] Git 工作区干净。
- [ ] 已创建 tag。
- [ ] release notes 已写明 Alpha 和已知限制。

## 9. Alpha 后第一轮迭代建议

发布后建议按优先级处理：

1. 收集安装失败、启动崩溃、数据库迁移失败、远程连接失败等 P0 问题。
2. 补 Room migration 自动化测试。
3. 补远程 API 兼容测试。
4. 补导入导出测试。
5. 做日志脱敏和 release 日志等级配置。
6. 增加基础 Compose UI 测试。
7. 将真机 smoke test 脚本化。
8. 评估 SMB、HTTPS/认证、扫描检查点等 beta 范围。

## 10. 发布建议结论

当前项目已经具备首个 Alpha 的功能基础，但不建议立即发包。建议先完成以下最小集合：

1. 配置 signed release。
2. 更新版本号。
3. 整理 Git 工作区。
4. 补一次手工 smoke test 记录。
5. 写 release notes。
6. 确定开源许可证。

完成后即可作为个人开源项目的首个 Alpha 发布；其余测试体系、日志策略和安全边界可以在 Alpha 发布后的短周期迭代中继续增强。
