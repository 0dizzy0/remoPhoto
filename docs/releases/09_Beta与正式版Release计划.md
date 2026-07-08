# remoPhoto Beta 与正式版 Release 计划

更新时间：2026-07-07

## 1. 文档目标

本文定义首个公开 Alpha `0.1.0-alpha.3` 之后的精简发布路线。项目由个人维护，门禁以防止数据损坏、升级失败、隐私泄漏和不可安装为核心，不追求团队项目式的流程完备。

当前功能行为以[功能规格](../product/02_功能规格说明.md)为准，测试断言以[核心功能测试用例](../testing/07_测试用例.md)为准。历史 Alpha 发布事实仍以[首个 Alpha Release 计划](08_首个AlphaRelease计划.md)和对应执行记录为准。

## 2. 当前基线

| 项目 | 状态 |
| --- | --- |
| 公开版本 | `0.1.0`，`versionCode = 6` |
| 开发候选 | 无；下一步观察正式版反馈 |
| Git Tag / 渠道 | `v0.1.0` / GitHub Release |
| Release 签名 | 已建立首个公开升级基线，密钥已独立备份 |
| JVM 测试 | `beta.1` 候选本地和 CI 19/19 通过 |
| Android Lint | `No issues found` |
| Release 构建 | signed APK、v2 签名和版本校验通过 |
| 真机回归 | `beta.1` 主力真机和 Android 16/12 双机远程回归均通过 |
| 当前重点 | 观察正式版反馈；如需 `0.1.1`，复用本计划门禁 |

## 3. 精简原则

1. 每次公开发布固定检查构建、签名、主力真机启动、升级、备份和日志隐私。
2. 数据库 schema、备份格式或签名发生变化时，必须增加对应迁移或恢复验证。
3. 只有远程相关代码变化时，Alpha 才重复完整双机回归；Beta 和正式版发布前至少执行一次。
4. API 29、API 35/36、UI 自动化、大仓库性能和故障注入按变更风险或实际缺陷安排，不要求每个 Alpha 全量执行。
5. 一个高价值回归测试优先于大量低收益测试；优先保护分页、排序、备份输入和最近迁移路径。
6. 明文 HTTP 只定位为可信局域网能力；没有访问控制时不得宣传为公网安全能力。
7. 公开 Tag 不移动。发现问题时递增版本并重新发布。
8. GitHub 标签、Milestone、会议记录和固定时间表均为可选，不作为个人项目发布门禁。

## 4. 默认版本路线

```text
0.1.0-alpha.3（当前公开基线）
        ↓
0.1.0-alpha.4（最小 CI、关键测试、实际反馈修复）
        ↓
0.1.0-beta.1（范围冻结、升级与双机验证）
        ↓
0.1.0（首个稳定版本）
```

`beta.2` 或 `rc.1` 不是固定步骤。只有 Beta 后修复 P0、修改数据库/备份/远程协议、升级 targetSdk 或进行大范围核心改动时，才增加一个候选版本重新验证。

所有公开版本的 `versionCode` 必须严格递增。若 Beta 前需要 `alpha.5`，继续使用下一个未占用整数。

## 5. `0.1.0-alpha.4` 计划

### 5.1 必须完成

| 任务 | 最小验收 |
| --- | --- |
| 建立最小 CI | push/PR 执行 `testDebugUnitTest`、`lintDebug`、`assembleDebug`；失败时保留可定位的报告 |
| 固定相册核心行为 | 覆盖分页、稳定排序、Unicode/空白名称和模糊定位 |
| 固定备份输入边界 | 覆盖 manifest/version、条目白名单、路径穿越和损坏 ZIP |
| 固定最近迁移路径 | Room v3→v4 migration 可重复验证，校验关键表和字段 |
| 处理实际严重问题 | 没有已知的安装、启动、数据损坏或迁移类 P0 |
| 验证公开升级 | `alpha.3 → alpha.4` 同签名覆盖安装，仓库、分类和设置保留 |
| 执行发布 Smoke Test | signed APK 在主力真机冷启动，本地仓库、浏览、备份恢复和 Crash buffer 正常 |

### 5.2 建议但不阻塞

- 从 GitHub 重新下载 `alpha.3` 或 `alpha.4` 附件后核对 hash 并安装。
- 远程 API 契约、数据库失败回滚或 1～3 条 UI 旅程。
- API 29、API 35/36 和大仓库性能专项。

上述建议项在相关代码发生变化、出现真实缺陷或手工回归频繁重复时升级为必须项。

### 5.3 退出标准

- [x] 最小 CI 稳定通过。
- [x] 分页/排序/定位、备份输入边界和 Room v3→v4 migration 测试通过。
- [x] 没有开放 P0。
- [x] `alpha.3 → alpha.4` 覆盖升级和数据保留通过。
- [x] signed APK、版本、签名、SHA-256、主力真机 Smoke Test 和 Release Notes 草稿齐全。

最终提交 `1b078f9` 的 GitHub Actions run `28589851108` 已通过；Tag、prerelease 和线上附件复验均已完成。最终 APK SHA-256 为 `2b4d2b2fb6fa3756f1d406d63e42eaf9e7595dca86d980b696cc01b2e47c52d7`。执行证据见[`0.1.0-alpha.4` 候选回归记录](../testing/2026-07-02_0.1.0-alpha.4_候选回归.md)。

## 6. `0.1.0-beta.1` 计划

进入 Beta 后默认冻结大功能，只接受 P0/P1 修复、兼容性修正、可观测性补强和小范围可回归改进。

Beta 发布前必须完成：

- [x] `alpha.4` 的必须项全部完成。
- [x] CI、Lint、JVM 测试和 signed Release 构建通过。
- [x] 最小 Room v3→v4 migration 可重复验证。
- [x] 有效备份恢复、非法输入拒绝通过；失败回滚至少有一次可重复证据或明确接受的残留风险。
- [x] `alpha.3 → beta.1` 或上一公开 Alpha → `beta.1` 覆盖升级后数据可用。
- [x] 两台真机完成发现、连接、分页、图片、缓存、离线和恢复回归。
- [x] Release 日志无 DEBUG、完整路径、URI、IP、设备名和用户自定义名称泄漏。
- [x] 主力真机完成本地核心、备份和冷启动回归。
- [x] README 和 Release Notes 明确安装、升级、校验与可信局域网限制。

API 29、API 35/36 和 UI 自动化不是 Beta 的固定全量门禁；当 minSdk/targetSdk、前台服务、SAF 或导航行为变化时执行对应专项。正式版前至少对 minSdk 和最新目标环境各完成一次基础兼容检查。

准备进度：2026-07-03 已完成 `TC-BKP-010` 可重复真机测试，在数据库替换和设置恢复后注入失败，原数据库、原设置、SQLite 完整性和临时目录清理均通过；上一公开版本覆盖升级和有效备份恢复随后均已完成。

候选进度：2026-07-03 已将开发版本更新为 `0.1.0-beta.1`（`versionCode = 5`），冻结大功能并完成首轮主力真机验证。`alpha.4 → beta.1` 覆盖升级、有效备份恢复、2 图/2 相册 SAF 本地核心、Release 日志隐私、Room migration、导入失败回滚和 Crash buffer 均通过。

2026-07-06 Android 16/12 双机远程门禁通过：mDNS、5249 个相册、268871 张图片、263 页分页、图片、缓存清理、服务离线和恢复均正常，Crash/ANR 为 0。README 和 Release Notes 已补齐安装、升级、校验与可信局域网限制。

`0.1.0-beta.1` 已于 2026-07-06 通过 GitHub prerelease 发布。Tag 指向提交 `0173c22`，最终 CI run `28796857392` 通过；线上 APK 与 SHA-256 附件回下载复验一致，最终 APK SHA-256 为 `c4af2efe86786016d6179bac6e0a916146dab6a17eaa6f02a85801bd16aaec69`。

## 7. `0.1.0` 正式版计划

正式版至少观察一个 Beta。项目试用人数较少时，以维护者持续使用和主动反馈为主要证据，不设置强制 14 天或 28 天期限。

正式版稳定承诺优先覆盖：

- 本地仓库添加、扫描、重扫和删除。
- 相册分页、排序、名称定位和图片浏览。
- 分类与设置持久化。
- 数据库导入导出和公开版本升级路径。
- Release 构建、签名和日志隐私。

远程仓库可以继续作为“可信局域网实验能力”，不要求为 `0.1.0` 强制增加 TLS 或令牌；但必须在 UI、README 和 Release Notes 中清楚标注安全边界。

正式版发布前必须满足：

- [ ] 没有开放 P0，本地稳定范围没有未接受的 P1。
- [ ] CI 通过；本地 Lint、关键单元测试和 signed Release 构建已于 2026-07-07 通过。
- [ ] 最新 Beta → `0.1.0` 覆盖升级成功；只有明确承诺从首个公开 Alpha 直接升级时才额外验证该路径。
- [x] 有效备份恢复和数据保留有可重复证据；非法输入拒绝、导入失败回滚、有效备份恢复、应用内备份导出日志隐私和 `beta.1 -> 0.1.0` 数据保留已有自动化或真机证据。
- [x] 主力真机本地核心通过；主力真机本地 SAF Smoke 已于 2026-07-07 通过，双机远程 Smoke 已于 2026-07-07 在 Android 12 + Android 16 通过。
- [x] minSdk 环境和 API 35/36 环境至少各完成一次安装、启动和基础浏览检查：2026-07-06 API 29 完成本地 SAF 浏览并修复 REL-012；Android 16/API 36 已完成 Beta 覆盖安装、冷启动和远程浏览。
- [x] 正式候选 APK 的 hash、签名、版本、安装和冷启动通过：2026-07-08 最新重建产物 SHA-256 `aa3a392eb1085008be9a16260419b80b18fd3326683eb179f399300d78c56eb1`，一键 Smoke 证据 `.cache/qa/smoke/20260708-230348/summary.md`。
- [x] Android 集成测试通过：2026-07-08 vivo Android 12 执行 instrumentation，Room migration、导入失败回滚和有效备份恢复 3/3 通过。
- [x] Release 日志隐私和仓库隐私审计通过；基础启动、本地 SAF、双机远程和备份导出流程扫描已通过，`content://`、存储路径、真实 IPv4、崩溃和 Crash buffer 包名命中均为 0。
- [ ] README、CHANGELOG、Release Notes 和安全说明同步。

如果 Beta 后只修改文档、元数据或低风险 UI，可以直接发布 `0.1.0`；如果修改数据库、备份格式、远程协议、签名、targetSdk 或核心事务逻辑，应增加 `beta.2` 或 `rc.1`。

## 8. 按风险触发的测试

| 变化 | 额外验证 |
| --- | --- |
| 仅文档或发布元数据 | 链接、版本说明和产物对应关系 |
| 普通 UI 或本地逻辑 | JVM 测试、Lint、构建、主力真机相关 Smoke |
| 数据库 schema / migration | 覆盖升级、迁移测试、数据保留 |
| 备份格式或导入逻辑 | 有效恢复、非法输入、路径安全；高风险修改再做失败回滚 |
| 远程协议、服务、缓存 | 双机发现、分页、图片、离线恢复和日志脱敏 |
| minSdk、targetSdk、AGP | 受影响 Android 版本的安装、启动、通知、前台服务和 SAF |
| 大仓库扫描或分页算法 | 稳定合成样本或现有大型仓库专项 |
| 签名配置 | `apksigner` 校验和旧公开版本覆盖安装 |

未修改模块可以引用最近一次同版本线记录，但必须说明引用依据。

## 9. 最小 CI

触发条件：

- 向 `master` push。
- 针对 `master` 的 Pull Request。
- 手动触发。

最低任务：

```text
:app:testDebugUnitTest
:app:lintDebug
:app:assembleDebug
```

要求：

- 使用 JDK 17 和 Gradle Wrapper。
- 依赖优先使用项目已有国内镜像，官方 Google/MavenCentral 作为兜底。
- 新增依赖前确认镜像可达且目标版本存在。
- CI 不保存 Release keystore，不输出环境变量、凭据或本地路径。
- 失败时上传或保留测试与 Lint 报告，确保问题可定位。

Instrumentation、模拟器、Release 签名和自动发布暂不进入默认 CI；出现稳定重复收益后再增加。

## 10. 精简发布流程

### 10.1 日常门禁

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

### 10.2 公开发布

1. 冻结本次范围，确认没有开放 P0。
2. 更新 `versionName`、递增 `versionCode`，同步 CHANGELOG 和 Release Notes。
3. 运行测试、Lint 和 signed Release 构建。
4. 使用计划发布的 signed APK 执行主力真机 Smoke Test。
5. 按第 8 节决定是否增加升级、备份、双机或兼容性专项。
6. 校验 APK 版本、签名和 SHA-256。
7. 提交代码，创建与版本一致的 Tag，上传 APK 和 SHA-256。
8. 重要节点从 GitHub 重新下载附件核对 hash；Alpha 可抽查，Beta 和正式版必须执行。

一次发布只需在 Release Notes 或简短检查记录中保留以下信息：

- 版本、commit、Tag 和 APK SHA-256。
- CI/本地门禁结果。
- 安装方式、设备和 Android 版本。
- 覆盖升级、备份和按风险触发测试的结果。
- Crash buffer、关键 AppLogger 摘要和已接受风险。

不再要求单独维护 Go/No-Go 会议模板、固定 Milestone 或冗长设备矩阵。

## 11. 暂缓事项

以下事项不阻塞 `alpha.4`、`beta.1` 或 GitHub `0.1.0`：

- SMB 电脑共享仓库。
- HTTPS/TLS 或局域网访问令牌。
- 视频播放和多用户权限。
- 完整 Compose UI 自动化。
- 全历史数据库 migration fixture。
- 每个版本执行完整 API 设备矩阵。
- 固定的大仓库性能指标。
- Play Store 上架材料和完整多语言。

这些事项只在真实用户需求、安全要求或维护收益明确时进入后续版本。

## 12. 立即执行顺序

1. 配置最小 GitHub Actions CI。
2. 补分页、排序和名称定位 JVM 测试。
3. 补备份 manifest/version、白名单、路径穿越和损坏输入测试。
4. 补最小 Room v3→v4 migration 测试。
5. 根据收到的 Alpha 反馈修复 P0/P1。
6. 执行 `alpha.3 → alpha.4` 覆盖升级、备份恢复和主力真机 Smoke Test。
7. 发布 `alpha.4`，之后再根据反馈决定进入 `beta.1` 的时间。

前 3 项完成前，不开始 SMB、视频或大规模界面重构。
