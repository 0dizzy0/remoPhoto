# SMB 远程仓库开发计划

更新时间：2026-07-12

开发分支：`codex/smb-support`

基线提交：`dc52b95`（包含相册分页修复及 UI Smoke）

## 0. 当前执行状态

截至 2026-07-12：

- M0 **部分完成**：基线、依赖源、SMBJ `0.14.0` 解析、独立 Android Spike、Debug 与 minified Release/R8 已通过；当前无 adb 设备，真机互通和资源压力门禁待执行，ADR 仍为“待真机验证”。
- M1 **本地回归完成、真机回归待执行**：协议无关契约、HTTP adapter、source router、opaque key、取消语义和远程日志脱敏已实现；JVM 32/32、Lint 和 app minified Release 通过。HTTP 双机 Smoke 需设备接入后补齐。
- 按门禁要求，M2、M3、Room v5、正式 SMB 依赖和 SMB UI 均未开始。

## 1. 目标、范围与默认约定

在 `0.1.0` 已有本地 SAF、remoPhoto HTTP/mDNS 远程仓库和远程缓存能力之上，增加 Android 客户端直接浏览 PC/NAS SMB 共享的能力。建议作为 `0.2.0` 的主功能开发，但在依赖兼容性 Spike 通过前不修改版本号或承诺发布日期。

首版以“用户主动配置的可信局域网、只读浏览”为边界：

- 只协商 SMB 2.0.2 及以上协议，明确禁用 SMB1；不宣称所有 SMB3 可选能力均受支持。
- 支持手动输入主机名或 IP、端口、共享名、可选子目录、用户名、密码和可选域。
- 支持连接测试、添加仓库、扫描相册、基于本地索引的分页浏览、缩略图、原图和手动刷新。
- 支持网络中断后的元数据离线展示；已进入 Coil 磁盘缓存的图片可离线查看，但不承诺所有原图均已缓存。
- 密码只进入现有 Android Keystore 凭据存储，不进入 Room、应用内备份、日志、异常文案、UI tree、图片模型或缓存键。
- 保持当前 HTTP/mDNS 远程仓库行为、内部标识和已有 Room 数据兼容。

首版不包含 SMB 服务端、文件写入/删除、自动发现、后台实时监听、公网访问、视频、Kerberos、DFS、多用户权限管理和 SMB 服务端生成缩略图。

已确认采用以下产品语义：

- SMB 共享名与可选子目录共同构成一个仓库根目录；仓库根本身也作为相册，因此根目录图片不会丢失。
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

退出条件：Spike 全部门槛通过，且业务代码尚未依赖具体 SMB API。

### M1：协议解耦与日志基线（P0）

- 新建协议无关 catalog/media 契约、错误类型和 router。
- 用 HTTP adapter 包装现有 API；迁移连接检查、相册同步、图片定位和 Coil fetch 路由。
- 保持 HTTP/mDNS 的 Room 数据、stable ID、URL 行为、分页结果和缓存键不变。
- 先补 HTTP fake server/contract tests，再做重构；补取消、分页中断、旧索引保留和错误映射测试。
- 清理远程链路现有 host、URL、相册名、路径日志，建立 Release 隐私扫描词典。

退出条件：不启用 SMB 时 JVM、UI Smoke、HTTP 双机 Smoke 与 minified Release 全部保持通过。

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
- 连接测试成功后才允许保存；配置变化后使上一次测试结果失效。
- 仓库卡片标识 SMB、连接状态、上次成功时间，并提供重新认证、刷新、取消扫描和删除。
- 错误文案由错误分类映射为可操作原因，不展示服务器原始异常、端点、路径或凭据。
- 覆盖 TalkBack 标签、焦点顺序、IME action、密码显隐、加载/失败状态、旋转/进程恢复和返回行为。

退出条件：Material 3 交互与可访问性检查通过，HTTP 添加流程无回归，UI tree 不含密码。

### M5：自动化、真机验收与文档（P0）

- JVM：路径规范化、identity key、stable ID、图片过滤、层级映射、快照差异、错误分类、协议路由、重试策略和缓存键。
- Instrumentation：Room v4→v5 migration、v4 备份导入、Keystore 存取/删除、缺凭据恢复和协议选择 UI。
- 单机无人值守 SMB Smoke：开发机提供临时共享，脚本完成前置检查、安装、添加、同步、进入相册、打开静态图/GIF、断线/恢复、远端增删刷新、删除仓库和结果汇总。
- Smoke 断言基于 UI tree、数据库/结构化日志状态和测试文件摘要，不依赖截图；保留关键截图仅作人工辅助证据。
- 兼容矩阵至少覆盖 Windows SMB3、Samba/NAS、Android 10/API 29 和一台较新 Android 真机。
- 更新 README、功能规格、架构 ADR、测试用例、第三方许可证、隐私说明和 Release Notes。

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

首个开发回合只完成 M0 和 M1。M0 未通过不得进入 Room、UI 或正式扫描实现；M1 回归未通过不得把 SMB 适配器接入主流程。

## 10. 已确认范围决策

2026-07-12 已确认三个范围问题均采用计划默认设置：

1. 首版 **不支持** NAS Guest/匿名共享，要求用户名和密码，避免匿名或空密码在不同库中产生不可控降级。
2. 采用“根目录也是一个相册、空目录不展示”的映射，确保共享根目录中的图片不会遗漏。
3. 应用内导出的备份 **允许** 包含 SMB 主机、共享名、用户名和子目录等连接元数据，但密码始终排除。
