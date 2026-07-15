# SMB 远程仓库开发计划

更新时间：2026-07-15

开发分支：`codex/smb-support`

基线提交：`dc52b95`（包含相册分页修复及 UI Smoke）

## 0. 当前执行状态

截至 2026-07-13：

- M0 **当前功能验证完成**：基线、依赖源、SMBJ `0.14.0` 解析、独立 Android Spike、Debug 与 minified Release/R8 已通过；Android 12 真机已完成 Windows SMB 认证和共享根枚举（`PASS: entries=63`）。API 29、Samba/NAS、资源压力和完整兼容矩阵按产品决定延后至 M5 上线前门禁。
- M1 **本地回归完成、HTTP 双机回归移至发布前**：协议无关契约、HTTP adapter、source router、opaque key、取消语义和远程日志脱敏已实现；JVM 32/32、Lint 和 app minified Release 通过。共享协议路由继续由自动化回归覆盖，HTTP 双机 Smoke 按 2026-07-13 范围决策移至后续综合回归/发布前执行，不阻塞 SMB M4。
- M2 **完成**：Room v5、规范化 `identity_key`、v4 迁移/备份导入、凭据生命周期与补偿、缺凭据恢复、SMB2/3 会话边界、并发/超时/取消、只读权限集、资源关闭及错误分类已实现；JVM 45/45、Android 12 真机迁移/导入/Keystore 4/4、Lint、Debug 与 minified Release 通过。API 29 与发布级完整门禁仍按决定保留到 M5。
- M3 **完成**：已实现共享根/子目录递归快照、图片过滤、相册层级映射、磁盘 spool、批处理、事务原子切换、失败保留旧索引、SMB 媒体引用、Coil 静态图/GIF 读取、确定性缓存键、只读流资源释放、网络约束 WorkManager、有界重试和删除时定向清理。JVM、Android 12 真机 M3 集成、Lint 与 minified Release 均通过；正式 app 已在真实 Windows SMB 上完成 155,794 张图片扫描与原子索引切换，并完成分页、静态图和动画 GIF 浏览，M3 的“真实 10,000+ 仓库”退出证据已补齐。
- M4 **完成**：添加远程仓库已提供 remoPhoto/SMB 协议选择；SMB 表单、局域网 mDNS + 445 端口兜底发现、手动填写、认证后目录浏览、已有仓库根目录修改、字段校验、临时凭据连接测试、测试失效、事务保存、首次后台刷新、状态与上次成功时间、手动刷新/取消、重新认证和删除均已接通。大型共享扫描不受整轮 30 秒连接超时约束，扫描起点不显示为“根目录”相册；错误和日志保持稳定分类与脱敏。JVM 64/64、Lint、Android 测试 APK、minified Release、Android 12 真机 SMB 集成 4/4 和 UI Smoke 均通过；真实 Windows SMB 已完成限定根目录 8448 张刷新、分页、静态图浏览，以及临时上移根目录后的 155,794 张全量扫描和动画 GIF 浏览。错误凭据重认证不会覆盖旧凭据，旧凭据可继续刷新；旋转和系统杀进程后非敏感表单状态恢复、密码清空。受控远端增删、独立仓库删除和有效新凭据重新认证均已闭环：服务端改密后强制新会话明确返回 `AUTH_FAILED`，重新认证成功后加密凭据确实替换，独立手动刷新仍保持 30 张图片与 `CONNECTED` 状态，日志未命中主机、共享名、用户名或密码标记。TalkBack 人工验收、remoPhoto HTTP 双机回归及其他明确延期项不计入 M4 门禁，M4 退出证据现已齐备。
- M5 **完成**：67/67 JVM、Lint、Android 测试 APK、minified Release/R8、API 29/31/36 instrumentation、Windows SMB3、WSL2 Samba 4.19.5、Debug/Release 中文目录与静态图/GIF、完整异常矩阵、单机无人值守 SMB Smoke、Release 隐私、Crash/ANR、许可证和 Bouncy Castle `1.84` 供应链门禁均通过。最终 API 29 Smoke 从临时共享同步 30 张，断线后保留旧索引，恢复后成功；远端新增到 31、删除回 30，最后删除应用仓库并恢复 Samba 配置。详细证据见 M5 验证记录。

## 1. 目标、范围与默认约定

在 `0.1.0` 已有本地 SAF、remoPhoto HTTP/mDNS 远程仓库和远程缓存能力之上，增加 Android 客户端直接浏览 PC/NAS SMB 共享的能力。建议作为 `0.2.0` 的主功能开发，但在依赖兼容性 Spike 通过前不修改版本号或承诺发布日期。

首版以“用户主动配置的可信局域网、只读浏览”为边界：

- 只协商 SMB 2.0.2 及以上协议，明确禁用 SMB1；不宣称所有 SMB3 可选能力均受支持。
- 支持自动发现同一 IPv4 /24 网段的 SMB 设备，也支持手动输入主机名或 IP、端口、共享名、可选子目录、用户名、密码和可选域。
- 认证后可浏览共享目录并选择相册根目录；已有仓库也可修改扫描根目录。
- 支持连接测试、添加仓库、扫描相册、基于本地索引的分页浏览、缩略图、原图和手动刷新。
- 支持网络中断后的元数据离线展示；已进入 Coil 磁盘缓存的图片可离线查看，但不承诺所有原图均已缓存。
- 密码只进入现有 Android Keystore 凭据存储，不进入 Room、应用内备份、日志、异常文案、UI tree、图片模型或缓存键。
- 保持当前 HTTP/mDNS 远程仓库行为、内部标识和已有 Room 数据兼容。

首版不包含 SMB 服务端、文件写入/删除、后台实时监听、公网访问、视频、Kerberos、DFS、多用户权限管理和 SMB 服务端生成缩略图。自动发现仅探测 SMB 服务端，不尝试绕过认证枚举共享名。

已确认采用以下产品语义：

- SMB 共享名与可选子目录共同构成仓库扫描边界，但扫描边界本身不显示为“根目录”相册；其直属目录直接成为顶层相册。边界内直属图片归入“未分类图片”，因此不会丢失。
- 根目录下每个子目录映射为一个相册，并保留父子目录关系；空目录不展示，除非其后代含受支持图片。
- 刷新成功后删除远端已不存在的索引；刷新失败或被取消时保留上一次完整索引。
- 主机名、共享名、用户名和子目录属于连接元数据，可随应用内数据库备份导出；密码永不导出。

## 2. 代码审计结论

当前可复用基础：

- `RemoteConnectionEntity` 已有 `SMB` 类型、主机、端口、共享名和用户名字段。
- `RepositoryEntity.remoteConnectionId`、远程相册/图片 Room 缓存、连接状态和离线展示已存在。
- `KeyStoreManager` 已能按连接 ID 加密保存密码；系统云备份与设备迁移已排除应用文件，数据库导入后也会删除对应本机凭据并重置连接状态。
- 本地扫描已有 IO dispatcher、临时 spool、可取消任务和“完整扫描成功后再替换索引”的实现经验。
- 相册、图片浏览、分类、分页、GIF 和远程 Coil 缓存 UI 已形成链路。

实现前必须处理的缺口：

| 现状 | 对 SMB 的影响 | 计划动作 |
| --- | --- | --- |
| `RemoteConnectionRepository` 和 `SyncRemoteRepositoryUseCase` 直接使用 HTTP DTO/API | SMB 被迫模拟 remoPhoto 服务端模型 | M1 引入协议无关目录快照与媒体读取接口 |
| HTTP URL 直接写入 `AlbumEntity.coverImagePath`、`ImageEntity.filePath` | 无法安全表达带凭据的 SMB 资源 | 使用不含凭据的内部 `RemoteMediaRef` 和确定性缓存键 |
| `RemoteImageFetcher` 只接收 HTTP 字符串并把 URL 写入日志 | 不能按连接 ID取凭据，且现有 Release 日志规则不够严格 | 拆分 HTTP/SMB Fetcher，并统一脱敏日志 |
| `RemoteConnectionDao` 只按 `host + port` 去重 | 同主机多个共享、账号或根目录会误判重复 | v5 增加规范化 `identity_key` 唯一键 |
| 连接表缺少 `domain`、`root_path` | 无法完整恢复 SMB 配置 | Room v4→v5 migration |
| 添加流程由 UI 直接拼装实体，且只做局部补偿回滚 | 数据库与 Keystore 可能出现孤儿状态 | 移入应用服务，采用事务加可恢复补偿流程 |
| 删除流程先删凭据和连接，再删仓库，未形成单一事务；未按仓库清 Coil 缓存 | 中途失败会形成残缺数据或残留缓存 | 建立统一 `RemoveRemoteRepositoryUseCase` |
| 当前多处日志包含 host、URL、相册名和路径 | SMB 路径/账号可能泄露 | M1 即完成远程链路日志基线整改 |
| 现有远程模块几乎没有契约级 JVM 测试 | 协议解耦回归风险高 | M1 先补 HTTP golden/contract tests |

## 3. 依赖决策门：M0 兼容性 Spike

截至 2026-07-12 的候选信息：

| 候选 | 当前可解析版本 | 定位与风险 | 计划优先级 |
| --- | --- | --- | --- |
| [SMBJ](https://github.com/hierynomus/smbj) | Maven Central `0.14.0` | Apache-2.0；原生面向 SMB2/SMB3；传递依赖含 Bouncy Castle、SLF4J 等，需验证 Android/R8、包体和资源释放 | 首选 Spike |
| [jcifs-ng](https://github.com/AgNO3/jcifs-ng) | Maven Central `2.1.10`；官方 README 仍示例 `2.1.9` | LGPL-2.1；官方说明为 SMB2.02、部分/实验性 SMB3，默认最低协议仍可能包含 SMB1，必须显式设置 min/max dialect | SMBJ 不通过后的备选 |

Spike 不修改正式业务层，产物放在独立试验 source set 或可整体删除的目录中。下载前先检查当前 `settings.gradle.kts` 的阿里云镜像与 Maven Central 均可达，并通过 Gradle dependency insight 确认目标版本实际存在、来源一致和校验和稳定；镜像不可达或缺版本时回退官方源，不临时加入未知仓库。

真机验证矩阵至少包含：

1. Android 10/API 29 与一台较新 Android 真机上的 Debug、minified Release 构建、安装和冷启动。
2. Windows SMB3 与 Samba/NAS 两类服务端，普通本地账号认证；记录实际协商 dialect、签名和加密状态，但日志不记录端点或账号。
3. 中文、空格、`#`、`%`、长路径、深层目录、空目录、损坏图片、GIF、超大图和大于 2GB 文件的 stat/受控读取行为。
4. 错误密码、共享不存在、权限不足、协议低于 SMB2、服务器离线、网络切换、超时、取消和恢复。
5. 连续枚举 10,000+ 文件、连续打开至少 100 张图片，检查句柄、线程、连接、内存、Crash/ANR 和明显卡顿。
6. APK/AAB 体积增量、完整依赖树、CVE/许可证检查、R8 keep 规则及第三方声明。

Spike 通过门槛：

- Debug 与 minified Release 均可在 API 29 真机完成认证、枚举和流式读图。
- SMB1 服务端必须明确失败；Windows 与 Samba/NAS 均至少协商 SMB2，首选库需完成 SMB3 互通。
- 取消、异常与循环读取后没有持续增长的文件/树/会话句柄，所有 `Closeable` 路径有测试或可观察日志。
- 未发现明文凭据、端点、共享名或远程路径进入 Release 日志、异常 UI、Room 媒体地址或缓存键。
- 形成 `docs/architecture/` 下的 ADR，记录版本、决策证据、保留风险和升级策略。

如 SMBJ 未通过，才执行等价的 jcifs-ng Spike；两者均未通过则停止后续实现，不在业务层堆叠兼容补丁。

## 4. 目标架构与数据契约

```text
ViewModel / WorkManager
          |
RemoteCatalogService（扫描、刷新、状态、错误映射）
          |
RemoteSourceRouter
          +-- HttpRemoteSource -> 现有 HTTP/mDNS API
          +-- SmbRemoteSource  -> SMB 客户端适配器
                                      |
                         CredentialStore + SmbSessionManager

Coil ImageLoader
          |
RemoteMediaFetcherRouter
          +-- HttpMediaFetcher
          +-- SmbMediaFetcher -> SmbSessionManager.openReadOnly()
```

不要把现有 HTTP 的分页 API直接抽象成所有协议都必须实现的 `listImages(page)`。SMB 枚举本质是目录快照，目标契约应围绕产品模型：

```kotlin
interface RemoteCatalogSource {
    suspend fun testConnection(config: RemoteConfig, credential: Secret): ConnectionReport
    fun scan(config: RemoteConfig, credential: Secret): Flow<CatalogBatch>
}

interface RemoteMediaSource {
    suspend fun open(ref: RemoteMediaRef): Source
    suspend fun stat(ref: RemoteMediaRef): RemoteFileStat
}
```

关键约束：

- `CatalogBatch` 使用协议无关的 opaque album/media key、相对路径、父 key、大小、修改时间和 MIME；UseCase 不接触 HTTP DTO 或第三方 SMB 类型。
- `RemoteMediaRef` 至少包含 `connectionId + opaqueMediaKey + variant + versionToken`。它不是可直接联网的 URL，不含 host、share、username、domain 或 password；Fetcher 通过连接 ID 查询 Room，再从 Keystore 取凭据。
- HTTP 适配器保留现有 ID、URL 解析和 Room 标识，M1 不迁移已有 HTTP 数据或改变缓存命中语义。
- SMB `opaqueMediaKey` 基于规范化相对路径生成；`versionToken` 使用 `size + lastModified`，服务端时间戳不可靠时不得误删或误判重复。
- 路径规范化只用于身份比较，不改变实际发送给服务端的原始名称；覆盖 `/` 与 `\`、`.`/`..` 拒绝、Unicode NFC、大小写策略、根目录越界和 IPv6/主机名序列化。
- 大小写策略由 Spike 对 Windows/Samba 实测后写入 ADR；在结论前不把两个只差大小写的路径静默合并。
- 扫描设置最大深度、单次最大条目数和循环检测，遇到 DFS、重解析点或目录循环时安全停止并给出可诊断错误。
- 只读取必要字段；扫描阶段不为每张图片下载完整文件获取宽高，未知宽高保留 `0`，由后续解码补充或保持未知。

`SmbSessionManager` 负责限定连接/读流并发、操作超时、空闲关闭、网络变化失效和凭据更新后的会话作废。任何第三方 client/session/share/file 对象都不能进入 Compose 状态或 Room；取消协程必须触发底层流和句柄关闭。

## 5. 数据、安全和一致性设计

### 5.1 Room v5

`remote_connections` 新增：

- `domain TEXT NULL`
- `root_path TEXT NULL`
- `identity_key TEXT NOT NULL`

`identity_key` 是规范化连接身份的 SHA-256，不含密码：

- HTTP：`type + normalizedHost + port`
- SMB：`type + normalizedHost + port + normalizedShare + normalizedRootPath + normalizedDomain + normalizedUsername`

为 `identity_key` 建唯一索引。v4→v5 migration 需为已有 HTTP 连接回填，并先检测潜在冲突；同时提交 v5 schema JSON、migration instrumentation test 和 v4 数据库备份导入测试。不能使用 destructive migration。

### 5.2 凭据与保存流程

- 连接测试使用仅存在于内存的表单凭据；认证失败不自动重试，避免账号锁定。
- 测试成功后，由应用服务串行执行：Room 事务创建 connection/repository（初始 `DISCONNECTED`）→ 写 Keystore 凭据 → 更新为 `CONNECTED`。任一步失败均执行幂等补偿清理。
- 若进程恰在 Room 提交后、Keystore 写入前退出，下次启动检测“SMB 连接缺凭据”，标记为需重新认证，不尝试空密码连接。
- 修改密码成功后使旧 session 立即失效；删除、导入、添加回滚和应用数据恢复路径都覆盖凭据清理测试。
- `KeyStoreManager` 不记录 alias 之外的敏感数据；密码临时缓冲在库 API允许时主动清零，异常对象不得直接透传 UI 或持久日志。

### 5.3 日志与错误

建立统一错误分类：`AUTH_FAILED`、`HOST_UNREACHABLE`、`SHARE_NOT_FOUND`、`ACCESS_DENIED`、`TIMEOUT`、`UNSUPPORTED_DIALECT`、`PATH_INVALID`、`CANCELLED`、`RESOURCE_LIMIT`、`UNKNOWN`。

关键阶段使用结构化、可观察日志，字段限定为：连接 ID、仓库 ID、阶段、批次号、耗时、条目数、协商协议、重试次数和错误分类。Release 中不得记录 host、port、share、domain、username、文件名、路径、URL、凭据和第三方异常原文。Debug 需要端点时也只允许显式散列或局部脱敏。

### 5.4 扫描提交与删除

- SMB 扫描沿用 spool/分批写入思路，避免 10,000+ 条目全部驻留内存。
- 新增/更新可分批进入 staging 或 generation；只有完整枚举成功后才在一个短事务内切换当前 generation 并删除陈旧索引。
- 失败、超时、取消、进程被杀或网络断开均保留上一完整 generation；启动时清理过期 staging/spool。
- 删除仓库由单一 UseCase 负责：先取消相关 Work、关闭 session、收集确定性缓存键；Room 事务删除图片/相册/仓库/连接；事务成功后删除 Keystore 与对应 Coil memory/disk key。外部清理失败要可重试，不影响其他仓库。
- 所有远程图片请求显式设置可重建的 memory/disk cache key；这样才能按连接精确清缓存，不采用“删除一个仓库就清空全部远程缓存”。

## 6. 分阶段实施

### M0：基线、测试环境与依赖 Spike（P0）

- 固化 `dc52b95` 的 JVM 26/26、UI Smoke 2/2、signed Release 与 HTTP 双机远程基线。
- 准备不含私人数据的 Windows 和 Samba/NAS 测试共享及一键初始化/销毁脚本；脚本输出服务状态、协议配置和测试数据摘要，不输出凭据。
- 按第 3 节完成首选库 Spike、依赖源可达性检查和 ADR。

当前开发阶段退出条件：依赖、Debug/minified Release 及当前 Android 12 真机认证和枚举通过，且业务代码尚未依赖具体 SMB API。第 3 节其余完整兼容性门槛移至 M5，在上线前必须全部完成。

### M1：协议解耦与日志基线（P0）

- 新建协议无关 catalog/media 契约、错误类型和 router。
- 用 HTTP adapter 包装现有 API；迁移连接检查、相册同步、图片定位和 Coil fetch 路由。
- 保持 HTTP/mDNS 的 Room 数据、stable ID、URL 行为、分页结果和缓存键不变。
- 先补 HTTP fake server/contract tests，再做重构；补取消、分页中断、旧索引保留和错误映射测试。
- 清理远程链路现有 host、URL、相册名、路径日志，建立 Release 隐私扫描词典。

退出条件：不启用 SMB 时 JVM、UI Smoke、HTTP contract/fake server 回归与 minified Release 保持通过；HTTP 双机 Smoke 移至后续综合回归/发布前执行。

### M2：SMB 数据、安全与生命周期骨架（P0）

- 完成 Room v5、`identity_key`、migration/schema/import 测试。
- 将添加、重新认证和删除流程移出 Compose，建立应用服务和幂等补偿。
- 实现 `SmbSessionManager`、连接测试、协议下限、只读 access mask、超时、并发限制和错误映射。
- 为所有 client/session/share/file/stream 路径加入 close/cancel 测试与计数日志。

退出条件：migration、去重、凭据生命周期、资源释放、错误映射和日志脱敏测试通过。

### M3：SMB 目录快照、刷新与媒体读取（P0）

- 实现共享根/可选子目录校验、递归枚举、图片过滤、父子相册映射和根相册。
- 实现 spool/generation 批处理、进度、取消、资源上限和成功后原子切换。
- 实现 `RemoteMediaRef`、SMB Coil Fetcher、静态图/GIF 读取和确定性缓存键。
- 缩略图首版由客户端采样解码并进入现有远程缓存；是否增加预取或范围读取只依据真机数据决定。
- WorkManager 只在有网络时运行，网络恢复不自动无限重试；认证/权限/协议错误不重试，瞬时 IO 错误采用有上限退避。

退出条件：真实 10,000+ 图片仓库可扫描、刷新、分页和浏览；取消、离线、恢复和远端删除均不破坏上一完整索引。

### M4：添加、管理与可访问性 UI（P0）

- “添加远程仓库”增加协议选择：remoPhoto 设备 / SMB 共享。
- SMB 表单包含显示名、主机、端口、共享名、子目录、用户名、域、密码和“测试连接”；字段校验不把原值写入错误日志。
- 提供 SMB mDNS 与本地 /24 网段 445 端口发现，发现结果只预填表单，手动填写始终保留。
- 认证后提供惰性目录浏览器选择相册根目录；已有 SMB 仓库可直接修改根目录并提交新刷新。
- 连接测试成功后才允许保存；配置变化后使上一次测试结果失效。
- 仓库卡片标识 SMB、连接状态、上次成功时间，并提供重新认证、刷新、取消扫描和删除。
- 错误文案由错误分类映射为可操作原因，不展示服务器原始异常、端点、路径或凭据。
- 覆盖基础 Compose 语义标签、焦点顺序、IME action、密码显隐、加载/失败状态、旋转/进程恢复和返回行为；不引入 TalkBack 依赖或专项人工朗读门禁。

退出条件：Material 3 交互、基础 Compose 语义和 UI tree 密码保护检查通过；共享协议选择、路由和刷新状态自动化无回归。HTTP 双机端到端回归不作为 SMB M4 退出条件。

### M5：自动化、真机验收与文档（P0）

- JVM：路径规范化、identity key、stable ID、图片过滤、层级映射、快照差异、错误分类、协议路由、重试策略和缓存键。
- Instrumentation：Room v4→v5 migration、v4 备份导入、Keystore 存取/删除、缺凭据恢复和协议选择 UI。
- 单机无人值守 SMB Smoke：开发机提供临时共享，脚本完成前置检查、安装、添加、同步、进入相册、打开静态图/GIF、断线/恢复、远端增删刷新、删除仓库和结果汇总。
- Smoke 断言基于 UI tree、数据库/结构化日志状态和测试文件摘要，不依赖截图；保留关键截图仅作人工辅助证据。
- 兼容矩阵至少覆盖 Windows SMB3、Samba/NAS、Android 10/API 29 和一台较新 Android 真机。
- 补齐 M0 延后的 API 29、Samba/NAS、资源压力、异常矩阵、Release 隐私与许可证/CVE 门禁；这些是上线门禁，不在当前功能开发期间阻塞 M2～M4。
- 更新 README、功能规格、架构 ADR、测试用例、第三方许可证、隐私说明和 Release Notes。

最终进度（2026-07-15）：

- 已完成：67/67 JVM、Lint、Android 测试 APK、minified Release/R8；API 29/31/36 instrumentation 各 12/12；Windows SMB3 与 Samba 4.19.5；HTTP 双机；API 29 Debug/Release；异常矩阵；单一 PASS/FAIL 无人值守脚本；Release 隐私、Crash/ANR；许可证与 CVE-2026-0636 修复复核。
- 环境说明：API 29 使用 Pixel 3 AVD；Samba 使用 E 盘 WSL2 Ubuntu 24.04 LTS。物理 NAS 不再是退出条件，因为计划要求的“独立 Samba/NAS”服务端已由独立 Samba 实现覆盖；不宣称验证了特定 NAS 厂商扩展。
- M5 无剩余功能门禁；`0.2.0` 版本号、签名候选、升级与发布授权进入后续发布流程。
- 详细证据见 [SMB M5 验证记录](../testing/2026-07-13_SMB_M5验证记录.md)。

退出条件：脚本最终只输出 PASS 或 FAIL，并在失败时给出阶段、错误分类和 UTF-8 证据目录；Release 日志隐私、Crash/ANR、资源泄漏和 minified 构建通过。

## 7. MVP 验收清单

- 能添加可信局域网内的 SMB2/3 共享；SMB1 明确失败且没有启用入口。
- 密码不出现在 Room、应用内备份、日志、异常文案、UI tree、图片引用和缓存键中。
- 中文、空格、特殊字符和嵌套目录可稳定映射；根目录图片可见；重复刷新不产生重复仓库、相册或图片。
- 同一主机/端口可添加不同共享、根目录或账号；完全相同 identity 不能重复添加。
- 相册分页、分类关联、静态图/GIF 网格和全屏浏览与 HTTP 远程仓库体验一致。
- 10,000+ 图片扫描可取消、可观察且不阻塞 UI；具体耗时和内存只记录实测基线，不提前承诺。
- 错误密码、离线、共享移除、权限不足、超时和低协议服务端均给出明确状态；恢复后可刷新，不要求重新添加。
- 失败或取消的刷新保留上一完整索引；成功刷新能反映远端新增、修改和删除。
- 删除 SMB 仓库后清除其 Work、session、索引、对应 Coil 缓存和 Keystore 凭据，不影响其他仓库。
- 原有本地 SAF、HTTP/mDNS、数据库导入导出和分页 Smoke 继续通过。

## 8. 风险与降级策略

| 风险 | 处理 |
| --- | --- |
| Android/R8 与 SMB 库不兼容 | M0 先验证 API 29 与 minified Release；失败才切换备选库 |
| SMB1 或弱协议被意外启用 | 配置、协商日志和专门负向测试三重保证最低 SMB2 |
| Windows/NAS 的签名、加密和 NTLM 差异 | Spike 记录协商能力；MVP 只承诺矩阵内通过的普通 NTLM 账号场景 |
| DFS、Kerberos、Guest 引入范围膨胀 | 默认排除，明确提示不支持，不用静默降级绕过安全策略 |
| 大仓库扫描慢或内存高 | 流式枚举、spool/generation、批量事务、进度、取消、资源上限和有界并发 |
| 缩略图流量和解码内存压力 | 采样解码、确定性 Coil 缓存、禁止默认预取原图，依据真机数据调参 |
| 服务端时间戳精度或时区不一致 | stable ID 以路径为主，版本 token 组合大小和修改时间；异常时允许重新取图 |
| 路径遍历、循环或根目录逃逸 | 相对路径规范化、拒绝 `..`、深度/条目上限、循环检测，DFS 留到后续版本 |
| 凭据、端点或路径泄露 | Keystore、内部引用、结构化脱敏日志、UI tree 检查和 Release 隐私扫描作为门禁 |
| DB 与 Keystore 无法同事务 | 串行应用服务、幂等补偿、启动自愈和故障注入测试 |
| 当前基线含已验证 UI 修复 | 新分支基于 `dc52b95`；SMB 按里程碑小提交，便于回退和二分定位 |

## 9. 建议执行顺序与提交边界

按 `M0 → M1 → M2 → M3 → M4 → M5` 顺序推进，不并行修改数据库、UI 和扫描内核。

建议提交边界：

1. `docs/test: record SMB dependency spike and ADR`
2. `refactor: introduce protocol-neutral remote contracts`
3. `test: preserve HTTP remote behavior through adapters`
4. `feat: migrate remote connection schema to v5`
5. `feat: add SMB session and credential lifecycle`
6. `feat: index SMB catalog snapshots`
7. `feat: load SMB media through Coil`
8. `feat: add SMB setup and management UI`
9. `test/docs: add SMB smoke, privacy gate and release docs`

M0 当前功能验证与 M1 回归通过后进入 M2；API 29 和完整发布矩阵按已确认决定延后至 M5，上线前仍为强制门禁。M1 回归未通过不得把 SMB 适配器接入主流程。

## 10. 已确认范围决策

2026-07-12 已确认三个范围问题均采用计划默认设置：

1. 首版 **不支持** NAS Guest/匿名共享，要求用户名和密码，避免匿名或空密码在不同库中产生不可控降级。
2. 仓库扫描边界本身不显示为“根目录”相册；直属目录提升为顶层相册，边界内直属图片归入“未分类图片”，确保图片不会遗漏。
3. 应用内导出的备份 **允许** 包含 SMB 主机、共享名、用户名和子目录等连接元数据，但密码始终排除。

2026-07-13 在 M4 代码审查后确认以下延期与风险接受决策：

1. 当前迭代 **暂不调整 SMB 签名策略**，继续沿用“用户主动配置的可信局域网”边界和现有 SMB2/3 协商行为。是否强制消息签名留待后续独立安全评估，不作为本轮 M4 退出条件。
2. SMBJ `0.14.0` 上游 [PacketReader 断线线程泄漏问题 #882](https://github.com/hierynomus/smbj/issues/882) 暂不纳入当前 M4/M5 门禁。项目先等待上游发布可用补丁或新版本，再单独完成版本、Android/R8 兼容性和断线资源回归；在此之前不维护业务层侵入式补丁。
3. 现有 SMB 添加对话框已满足 M4 功能接入，本轮 **不进行** ViewModel 状态机迁移、组件拆分或大范围视觉重构。后续建立独立 UI 优化任务处理交互、状态恢复和代码解耦，该优化不阻塞本轮 M4 功能验收。
4. 项目 **不引入 TalkBack SDK、依赖或专项适配层**，TalkBack 人工听觉验收不作为 SMB M4 或当前项目门禁。继续保留按钮说明、密码语义、合理焦点顺序和 48dp 点击区域等低成本基础可访问性规范，并以 Compose 语义树/UIAutomator 自动检查防止明显回归。
5. remoPhoto HTTP 与 SMB 不建立功能依赖。共享远程仓库入口、协议路由、刷新/取消状态和删除生命周期继续由自动化回归覆盖；需要第二台设备的 HTTP 发现与手动添加端到端测试移至后续综合回归/发布前执行，**不阻塞 SMB M4 结束**。
6. SMB 与 HTTP 的远端目录增量增删同步作为后续可选能力单独规划，**不纳入当前 M4 门禁，也不自动升级为 M5 发布门禁**。在增量协议和回退机制完成验证前，现有全量快照同步继续作为权威兜底路径，具体演进计划见第 11 节。

## 11. 后续功能演进：SMB/HTTP 增量增删同步

### 11.1 目标与边界

该能力用于识别远端仓库中新增、修改、删除、重命名或移动的媒体，并只更新受影响的本地索引和缓存，以降低大仓库重复扫描、网络传输和数据库写入成本。这里的“增删”是指 **发现并同步服务端已发生的变化**；SMB 仓库仍保持只读，本计划不授权客户端向远端创建、修改或删除文件。

本项属于 M4 之后的候选功能，暂不承诺具体版本。是否进入开发由大仓库性能数据、服务端协议能力和兼容性验证共同决定。

### 11.2 当前实现基线

- SMB：从配置根目录执行完整广度优先枚举，生成完整 spool/generation 后再应用数据库快照；应用阶段会复用稳定相册 ID，但图片索引仍按仓库删除后批量写入。当前没有目录变更通知、增量游标或可靠的子树跳过机制。
- HTTP：每次同步先获取完整相册列表，并对相册元数据执行新增、更新和过期删除；随后逐相册分页获取全部媒体，再删除该相册旧图片并批量写入新结果。当前 HTTP 协议没有媒体变更游标、删除墓碑或快照版本边界。
- 仓库删除：删除的是本地仓库配置、索引、凭据、会话和相关图片缓存，不删除 SMB/HTTP 服务端文件；外部清理失败的持久化重试仍需单独设计。

### 11.3 分阶段实现建议

#### 阶段 A：全量发现、差异应用

先保持服务端完整枚举，降低协议改造风险，但不再无条件重写全部本地索引：

1. 统一远端条目标识与版本指纹。条目标识至少包含连接 ID 和规范化远端路径；版本指纹优先组合文件大小、修改时间以及 HTTP 服务端可提供的修订号。
2. 在 staging generation 中计算 `added`、`modified`、`deleted`、`unchanged` 集合，只批量写入新增或变化记录并删除已消失记录。
3. 未变化条目跳过数据库写入并保留稳定本地 ID、缓存键和用户侧元数据；移动/重命名在无法取得服务端稳定文件 ID 时可先按“删除 + 新增”处理。
4. 差异提交保持事务性；取消、超时、枚举失败或进程中断时不替换上一份有效索引。

此阶段能减少数据库和缓存抖动，但仍需扫描完整远端目录，不能宣称为真正的远端增量同步。

#### 阶段 B：协议级增量发现

SMB 方向：

1. 验证 SMB2/3 `CHANGE_NOTIFY` 在 Windows 与目标 NAS 矩阵中的行为、断线恢复、递归监听限制和 SMBJ Android/R8 兼容性。
2. 变更通知只作为增量提示，不作为唯一事实来源；离线、通知丢失、会话重连、溢出或服务端不支持时，触发受影响目录或全仓库对账。
3. 可评估目录指纹辅助缩小扫描范围，但不得仅凭目录修改时间跳过子树；首版不依赖 Windows 专属 USN Journal，以免破坏 NAS 兼容性。

HTTP 方向：

1. 服务端增加单调 `catalogRevision`/cursor 和分页 change feed，变化记录至少包含 upsert、delete tombstone、稳定媒体 ID、版本和快照边界。
2. 客户端幂等应用变化，只有在整批事务成功后才持久化新 cursor；重复、乱序或跨页重试不得造成重复记录或漏删。
3. cursor 过期、服务端重置、协议版本变化或分页快照失效时自动回退全量同步，并在成功后建立新基线。

#### 阶段 C：对账与运维保障

1. 保留手动“完整重新扫描”入口，并在周期性维护、长时间离线或连接恢复后执行低频全量对账。
2. 协议层输出统一同步结果语义，例如 `FULL`、`DELTA`、`RECONCILE`，共享新增/修改/删除/未变化计数，但 SMB 与 HTTP 不强制共用具体发现机制。
3. 关键日志仅记录同步模式、修订号、条目计数、耗时、回退原因和错误分类，不记录凭据、主机、共享名或完整远端路径。

### 11.4 验收与进入开发的条件

进入正式实现前应先用 1 万和 10 万级仓库取得全量同步基线，确认扫描耗时、远端调用次数、传输量、数据库写入量或电量中至少一项已构成实际瓶颈，并分别完成 SMB 通知能力与 HTTP change feed 的技术验证。

实现后的自动化与真机/NAS 回归至少覆盖：

- 单文件与批量新增、修改、删除、重命名、跨目录移动及空目录变化；
- 断网期间发生变化、通知丢失/溢出、cursor 过期、重复或乱序事件；
- 取消、超时、进程死亡和事务失败后仍保留上一份有效索引；
- 未变化记录不被重写，稳定 ID 按设计保留，已删除条目的索引与缓存被正确失效；
- 增量失败能够自动、可观测地回退全量对账，且最终结果与一次完整扫描一致；
- Debug 与 Release 日志、UI tree 和导出产物继续通过隐私门禁。

在上述能力验证完成前，全量快照仍是正确性基线；增量同步不得以牺牲漏删检测、事务原子性或跨服务端兼容性换取表面性能。
