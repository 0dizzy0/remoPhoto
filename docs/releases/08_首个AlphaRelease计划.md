# remoPhoto 首个 Alpha Release 计划

更新时间：2026-06-28

## 1. 发布目标

本次目标不是发布“功能完全稳定版”，而是发布个人开源项目的首个可试用 Alpha 版本：

- 能让作者和少量早期用户从源码或安装包体验核心功能。
- 能收集真实设备、真实图片仓库、局域网远程仓库场景下的问题。
- 能证明项目具备基本工程可维护性：可构建、可签名、可回归、可定位日志、可回滚。
- 明确标注已知限制，避免把尚未验证充分的功能包装成稳定承诺。

本次版本已确定为 `0.1.0-alpha.3`（`versionCode = 3`），计划使用 Git tag `v0.1.0-alpha.3`。

## 2. 当前状态摘要

| 项目 | 当前状态 | Alpha 发布判断 |
| --- | --- | --- |
| Debug 构建 | `:app:assembleDebug` 通过 | 达标 |
| Release 构建 | `:app:assembleRelease` 通过 | 基本达标 |
| Release 产物 | `app-release.apk`，v2 签名与 Android 12 全新安装通过 | 覆盖安装缺少同签名旧版基线 |
| JVM 单元测试 | 10 个用例通过 | 可作为 alpha 起点，但覆盖仍较薄 |
| Android 仪器/Compose 测试 | 暂无 | 不阻塞 alpha，但需要列入后续 |
| Lint | 2026-06-27：`No issues found` | 达标；含 2 项局部、带原因的例外 |
| Room 数据库 | 当前版本 v4，schema 已导出，迁移链 1->2->3->4 存在 | schema 达标，自动迁移测试后续补充 |
| 远程仓库 | 双机验证 5244 相册、263 页、图片、缓存清理、离线和恢复 | 主流程及 REL-009 修复复测达标 |
| 扫描大仓库 | 已引入 WorkManager、Spool、事务切换 | 可纳入 alpha，需保守说明 |
| 发布文档 | 已有计划、检查清单、Release Notes 草稿、核心真机和双机远程记录 | 待最终发布提交、日期和 Tag |
| Git 状态 | 发布相关改动已分提交；仍有明确排除的本地改动 | 发布 tag 前需确认或清理剩余改动 |
| GitHub 基础文档 | README、贡献、安全、更新日志和模板已补齐 | 基本达标 |
| 开源许可证 | MIT License | 许可证选择已完成 |

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

### A2. 配置 Release 签名（本机配置已完成）

本地签名材料位于被 Git 忽略的 `.signing/`，构建脚本也支持 `REMOPHOTO_RELEASE_*` 环境变量。首次初始化脚本：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-release-signing.ps1 `
  -KeytoolPath "<JDK目录>\bin\keytool.exe"
```

脚本不会输出密码或覆盖已有密钥。`.signing/` 必须离线备份，不能提交仓库。

验收标准：

- 能生成 `app-release.apk` 或等价 signed APK/AAB。
- 安装到真机成功。
- `adb shell dumpsys package com.remophoto` 能看到版本号符合本次发布。

建议命令：

```powershell
.\gradlew.bat :app:assembleRelease --console=plain
```

2026-06-27 已生成 `app/build/outputs/apk/release/app-release.apk`；`apksigner` 显示 `Verifies`，v2 签名通过。真机安装与版本核对并入 A6 smoke test。

### A3. 确定 Alpha 版本号（已完成）

当前配置：

- `versionCode = 3`
- `versionName = "0.1.0-alpha.3"`

验收标准：

- APK 元数据中的 versionCode/versionName 与发布说明一致。
- Git tag 与 versionName 一致，即 `v0.1.0-alpha.3`。

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

### A5. Room Schema 导出（已完成）

当前已在 `app/build.gradle.kts` 中配置 `room.schemaLocation`，v4 schema 位于：

- `app/schemas/com.remophoto.data.local.AppDatabase/4.json`

后续数据库版本升级时必须继续提交新 schema，并基于历史 schema 增加 migration 测试。

2026-06-27 执行 `:app:kspDebugKotlin` 已成功生成 schema；随后 `:app:assembleRelease` 通过，未再报告 Room `schemaLocation` 告警。

### A6. 最小发布回归

Alpha 前至少完成一轮手工 Smoke Test，范围以[核心功能测试用例](../testing/07_测试用例.md)为准，结果使用[测试执行记录模板](../testing/测试执行记录模板.md)归档并链接到 Release Notes。

最低覆盖：

- 冷启动。
- 覆盖安装。
- 添加本地仓库。
- 小仓库扫描。
- 使用小型合成元数据验证相册分页边界。
- 相册排序和模糊定位。
- 打开相册和全屏图片。
- 设置页切回相册列表。
- 开启远程服务。
- 添加远程仓库。
- 使用小型远程仓库或服务端 fixture 验证跨页和末页分页。
- 断开 Wi-Fi 后分类中远程相册隐藏。
- 恢复网络后远程仓库重新连接。
- 数据库导出。
- 数据库导入或至少导入文件格式校验。
- crash buffer 无新增 `FATAL EXCEPTION`。

执行结果不得直接堆叠到测试设计正文，应复制[测试执行记录模板](../testing/测试执行记录模板.md)形成独立记录。

### A7. 确定开源许可证（已完成）

项目采用 MIT License，完整文本位于仓库根目录 `LICENSE`，README 已同步许可证说明。

验收标准：

- GitHub Release 使用 MIT License 表述。
- 第三方依赖许可证与选定分发方式不存在明显冲突。

## 5. Alpha 强烈建议项

这些不一定阻塞 alpha，但完成后可以显著降低早期试用风险。

### B1. 发布日志降噪和脱敏（已完成基础治理）

当前策略：

- Debug 构建保留 DEBUG、Logcat 和原始诊断信息。
- Release 构建最低为 INFO，关闭 Logcat 并过滤 DEBUG。
- Release 文件日志统一脱敏 URI、文件路径、IPv4/IPv6、host 和用户自定义名称。
- 数量、耗时、稳定 ID 和错误类型继续保留，保证问题可定位。

验收标准：

- Alpha release 日志仍能定位扫描、同步、导入导出、远程连接问题。
- 不在正常浏览流程中持续刷大量 DEBUG 日志。

截至 2026-06-28，日志脱敏单元测试已扩充到 6 个并全部通过；最终 signed Release APK 的本地和双机真机日志抽查通过。

### B2. Manifest 发布策略复核（已完成）

当前 manifest 中存在：

- `android:allowBackup="false"`
- `android:dataExtractionRules="@xml/data_extraction_rules"`
- `android:usesCleartextTraffic="true"`

决策：

- 系统云备份和设备迁移均排除数据库、设置、SAF 路径和远程连接元信息。
- 用户数据迁移使用应用内导入导出。
- 动态局域网 IP 无法用静态域名白名单表达，因此保留明文 HTTP。
- README 和安全说明明确限制为可信局域网，不宣传为公网安全方案。

验收标准：

- 发布说明中包含远程访问安全提示。
- 不宣传为安全的公网访问方案。

### B3. 自动化测试补充

Alpha 前最低门禁已完成：当前 10 个 JVM 测试通过，并完成本地核心和双机远程手工回归。

Alpha 后第一优先级建议补：

- 相册分页边界测试。
- 相册排序稳定性测试。
- 相册名称定位测试。
- 导入导出 manifest/version 和路径白名单的 JVM 测试。
- 远程 API DTO、分页参数和错误映射的 JVM 测试。

完成以上低门槛测试后，再补最小 Room v3→v4 migration、备份回滚、缓存 TTL 和少量 Compose 核心旅程。大型仓库、全历史迁移和完整 adb 回归属于按需专项，不要求个人开发者先准备数千相册或数十万图片。

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

最低使用 1 台主力手机和 D1 小仓库完成本地功能。首次公开 Alpha 若包含远程能力，再使用 2 台同局域网设备和 D5 小仓库完成一次远程模块包；大型仓库不是发布前置条件。

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
git tag v0.1.0-alpha.3
```

如果推到 GitHub：

```powershell
git push origin <branch>
git push origin v0.1.0-alpha.3
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

- [x] `versionCode` 已递增。
- [x] `versionName` 已更新为本次 alpha。
- [x] Release 签名配置可用。
- [x] 生成 signed release APK/AAB。
- [x] `:app:testDebugUnitTest` 通过。
- [x] `:app:lintDebug` 通过并输出 `No issues found`。
- [x] `:app:assembleRelease` 通过。
- [x] Room schema 导出问题已处理或明确记录。
- [ ] 真机覆盖安装成功。
- [x] 冷启动无崩溃。
- [x] 本地仓库扫描可用。
- [x] 小型合成数据的分页边界可用。
- [x] 相册查找可用。
- [x] 基本全屏浏览可用；多图翻页仍待补测。
- [x] 设置页切换导航可用。
- [x] 远程服务启动、关闭和重新开启生命周期可用。
- [x] 远程仓库可添加。
- [x] 远程仓库分页可用。
- [x] 网络断开和恢复表现符合预期；恢复需要对现有仓库显式刷新。
- [x] 数据库导出可用。
- [x] 导入流程已完成有效备份和损坏 ZIP 基本校验。
- [x] crash buffer 无新增崩溃。
- [x] AppLogger 隐私抽查通过；文件名、相册/仓库/分类实体、路径、URI、IP 和设备别名已脱敏。
- [ ] Git 工作区干净。
- [ ] 已创建 tag。
- [ ] release notes 已写明 Alpha 和已知限制。

## 9. Alpha 后第一轮迭代建议

发布后建议按优先级处理：

1. 收集安装失败、启动崩溃、数据库迁移失败、远程连接失败等 P0 问题。
2. 补分页、排序和名称定位 JVM 测试。
3. 补导入校验和远程 DTO/分页规则 JVM 测试。
4. 补最小 Room v3→v4 migration 测试。
5. 根据缺陷收益增加备份回滚、缓存或基础 Compose 测试。
6. 在重复手工成本明显时再脚本化真机 smoke。
7. 评估 SMB、HTTPS/认证、扫描检查点等 beta 范围。

## 10. 发布建议结论

当前项目已经具备首个 Alpha 的功能基础，核心发布回归已通过。发包前还需完成以下发布整理：

1. 明确没有同签名旧版迁移基线的发布处理方式。
2. 离线备份 Release keystore。
3. 审核并提交最终候选改动，补齐 release notes 的 Commit 和日期。
4. 整理 Git 工作区并创建发布 Tag。

完成后即可作为个人开源项目的首个 Alpha 发布；其余测试体系、日志策略和安全边界可以在 Alpha 发布后的短周期迭代中继续增强。
