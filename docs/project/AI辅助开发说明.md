# remoPhoto AI 辅助开发说明

更新时间：2026-06-29

## 1. 使用声明

remoPhoto 在需求整理、架构分析、代码实现与审阅、问题定位、测试设计与执行、文档维护和发布准备过程中使用 AI 辅助开发。

AI 是开发工具，不是发布责任主体。项目维护者负责：

- 确定需求范围和优先级。
- 提供真机、局域网和测试数据环境。
- 决定是否接受 Alpha 残留风险。
- 管理 Release 签名材料。
- 审核 Git 变更并授权最终发布。

AI 生成或修改的内容必须经过与风险相匹配的验证。本次 Alpha 使用的主要门禁包括 Gradle 构建、Android Lint、JVM 单元测试、APK 签名与版本校验、仓库隐私审计、Android 真机核心回归和双机远程回归。

## 2. AI 代理与仓库指令

| 项目 | 状态 | 用途 | 可追溯入口 |
| --- | --- | --- | --- |
| OpenAI Codex | 实际使用 | 需求与代码分析、修改、测试、文档和发布自动化 | 当前 Codex 工作区与 Git 提交 |
| `AGENTS.md` | 已纳入版本控制 | 统一中文回复、依赖安装位置、国内镜像、日志和 UTF-8 等仓库级约束 | 仓库根目录 `AGENTS.md` |
| `CLAUDE.md` | 已纳入版本控制 | Claude 兼容入口，复用 `AGENTS.md` 的同一组约束 | 仓库根目录 `CLAUDE.md` |
| Claude 本地工作区 | 本地配置存在 | 可选的兼容开发环境；存在配置不代表每个提交均由 Claude 参与 | `.claude/settings.local.json`，由 Git 忽略 |

Codex 的具体模型和宿主版本由执行环境管理，并非 Android 构建依赖，当前仓库不锁定该版本。Git 提交、测试记录和发布门禁是项目结果的可追溯依据。

## 3. Skills 与插件清单

以下清单按“本次确认使用”和“工作区已安装”区分，避免把未调用能力写成已使用。

| 名称 | 类型 | 状态 | 来源或位置 | 用途 |
| --- | --- | --- | --- | --- |
| `github:github` | Codex 插件 Skill | `0.1.0-alpha.3` 发版流程确认使用 | Codex GitHub 插件 `0.1.5`，本地插件缓存 | GitHub 发布工作流和仓库操作边界；建仓、推送和 Release 在连接器覆盖不足时使用 `gh` CLI |
| `mobile-android-design` | 工作区 Skill | 已安装，按 Android UI/Compose 任务使用 | `wshobson/agents`；本地路径 `.agents/skills/mobile-android-design/` | Material Design 3、Jetpack Compose、导航、主题和无障碍设计指导 |
| `find-skills` | 工作区 Skill | 已安装，按 Skill 发现任务使用 | 本地路径 `.codex/skills/find-skills/` | 搜索、评估和安装可复用 Agent Skills |
| `chrisbanes-skills@chrisbanes-skills` | Claude 插件 | 本地启用 | `.claude/settings.local.json` | 向 Claude 本地工作区提供额外 Skills；具体能力和版本未在仓库固化，配置不随仓库发布 |

`mobile-android-design` 的本地 `skills-lock.json` 记录：

- 来源：`wshobson/agents`
- Skill 路径：`plugins/ui-design/skills/mobile-android-design/SKILL.md`
- 内容哈希：`143a60d6a0744a5058adf984e3a64b75cd2ef3714a15557f4ff6fd95e39ff23a`

该锁文件及 Skill 内容按当前仓库策略由 Git 忽略。它们属于开发环境，不是应用依赖。

## 4. MCP 状态

截至 2026-06-29：

- `.codex/config.toml` 没有配置任何项目级 MCP Server。
- 本次 `0.1.0-alpha.3` 发布流程没有通过 MCP 读取项目数据、用户图片、签名材料或 GitHub 凭据。
- Codex 或其他 AI 客户端可能由宿主环境动态提供额外能力；只有实际调用并与项目相关的能力才应记录为本项目使用，不能把宿主环境全部可用工具视为项目依赖。

后续如果增加 MCP，必须在本文记录：

1. Server 名称、来源和版本。
2. 可访问的数据和允许执行的操作。
3. 凭据保存位置与最小权限策略。
4. 是否会访问源码、日志、图片或其他敏感数据。
5. 禁用和移除方式。

## 5. 非 Skill/MCP 工具

下列工具参与了开发或发布，但不属于 Skill 或 MCP：

| 工具 | 用途 |
| --- | --- |
| Gradle Wrapper | 构建、JVM 测试和 Android Lint |
| ADB | 真机安装、UI 操作、状态与日志检查 |
| Git | 版本控制、提交和 Tag |
| GitHub CLI `gh` | GitHub 认证、建仓、推送和 Release 管理 |
| `apksigner` / `aapt2` | APK 签名、版本和 SDK 元数据校验 |
| WebSearch / WebFetch | 查询官方文档、版本和下载地址 |
| PowerShell 脚本 | 签名初始化与备份、仓库隐私审计和发布校验 |

## 6. 数据、安全与发布边界

- remoPhoto 应用本身不包含运行时 AI 功能，不调用 AI API，也不会把用户图片发送给 AI 服务。
- `.agents/`、`.codex/`、`.claude/`、`.cache/` 和 `skills-lock.json` 是本地开发环境内容，默认由 Git 忽略，不打包进 APK。
- `.signing/`、签名密码、APK、日志、数据库、截图和真实测试证据不进入 Git。
- AI 不应输出、记录或提交签名密码、访问令牌、完整用户路径、SAF URI、相册名称、设备名称或局域网地址。
- 公开日志和测试记录必须使用合成数据或完成脱敏，并通过仓库隐私审计。

## 7. 维护规则

1. AI 工具链发生变化时更新本文日期和对应清单。
2. 新增 Skill 时记录来源、用途、是否实际使用及是否随仓库分发。
3. 新增 MCP 前先完成数据访问和凭据权限审查。
4. 不用“纯 AI 自动完成”替代可验证的责任描述；人工决策和发布授权必须明确。
5. AI 参与不降低构建、测试、隐私和发布门禁。
