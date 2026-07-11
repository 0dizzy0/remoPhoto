# SMB 远程仓库开发计划

更新时间：2026-07-11

## 1. 目标与版本定位

在 `0.1.0` 已有本地 SAF、remoPhoto HTTP/mDNS 远程仓库和远程缓存能力之上，增加 Android 客户端直接浏览 PC/NAS SMB 共享的能力。建议作为 `0.2.0` 的主功能开发，但在依赖兼容性 Spike 通过前不修改版本号或承诺发布日期。

首版以“可信局域网内、只读浏览”为边界：

- 支持 SMB 2/3；明确禁用 SMB1。
- 支持手动输入主机、端口、共享名、可选子目录、用户名、密码和可选域。
- 支持连接测试、添加仓库、扫描相册、分页浏览、缩略图、原图和手动刷新。
- 支持网络中断后的缓存展示、错误状态和恢复刷新。
- 密码只进入现有 Android Keystore 凭据存储，不进入 Room、备份、日志或图片 URL。
- 保持当前 HTTP/mDNS 远程仓库行为和已有数据兼容。

首版不包含 SMB 服务端、文件写入/删除、自动发现、后台实时监听、公网访问、视频、Kerberos 和多用户权限管理。

## 2. 当前代码基础与主要缺口

当前已有可复用基础：

- `RemoteConnectionEntity` 已有 `SMB` 类型、主机、端口、共享名和用户名字段。
- `RepositoryEntity.remoteConnectionId`、远程相册/图片 Room 缓存、连接状态和离线展示已存在。
- `KeyStoreManager` 已能按连接 ID 加密保存密码，数据库导入后会删除对应本机凭据并重置连接状态。
- 相册、图片浏览、分类、分页和远程缓存 UI 已形成完整链路。

实现 SMB 前需要先处理的结构问题：

- `RemoteConnectionRepository`、`SyncRemoteAlbumsUseCase` 和远程图片 URL 生成目前直接依赖 HTTP DTO/API。
- `AddRemoteRepoDialog` 只支持 mDNS 和 HTTP 主机/端口输入。
- `RemoteImageFetcher` 只识别 HTTP URL；SMB 凭据不能拼入 `smb://` URL。
- 当前连接表缺少 `domain` 和共享内 `rootPath`，需要 Room v4→v5 migration。
- SMB 没有 remoPhoto 服务端提供的相册元数据和缩略图 API，目录扫描、稳定 ID、图片解码和缓存键必须由客户端完成。

## 3. 依赖决策门：先做兼容性 Spike

候选库先保留两个，不在计划阶段直接锁定：

| 候选 | 优点 | 风险 |
| --- | --- | --- |
| [SMBJ](https://github.com/hierynomus/smbj) | Apache-2.0；原生面向 SMB2/SMB3；Maven Central 有 `0.14.0` | 需验证 Android API 29、R8、加密依赖、APK 增量和真机资源释放 |
| [jcifs-ng](https://github.com/AgNO3/jcifs-ng) | API 成熟、支持流式目录枚举和按上下文配置 | LGPL-2.1；官方范围主要为 SMB2.02/2.1 和部分 SMB3；必须显式禁用 SMB1 |

Spike 必须使用真实 Android 真机，不以纯 JVM 成功代替 Android 兼容结论。对两个候选分别完成：

1. Debug 与 minified Release 构建、安装和冷启动。
2. 连接 Windows/Samba 测试共享，完成认证、列目录、读取中文/空格/长路径文件和打开大图。
3. 错误密码、共享不存在、服务器离线和连接恢复。
4. 连续打开至少 100 张图片，检查句柄/连接释放、Crash/ANR、内存和明显卡顿。
5. 记录 APK 体积增量、依赖树、许可证义务和 R8 keep 规则。

决策标准按顺序为：真机稳定性与 SMB3 互通性、只读最小权限、安全和许可证、维护活跃度、包体与实现成本。Spike 结论单独写执行记录；未通过时停止后续实现，不把试验代码混入业务层。

## 4. 目标架构

先建立协议无关接口，再接 SMB：

```text
相册/图片 ViewModel
        |
RemoteRepositoryGateway
        |
        +-- HttpRemoteSource  -> 现有 HTTP/mDNS API
        |
        +-- SmbRemoteSource   -> SMB 客户端库
                 |
          KeyStoreManager + SMB session lifecycle
```

建议协议接口只暴露产品需要的只读能力：

- `testConnection(connection, credential)`
- `listAlbums(connection)`
- `listImages(connection, albumKey, page, pageSize)`
- `openImage(connectionId, remotePath)`
- `readMetadata(connectionId, remotePath)`

HTTP 适配器包装现有 `RemoteHttpClient`，SMB 适配器负责目录遍历和流读取。ViewModel、UseCase 和 Room 同步逻辑只依赖协议接口，不再判断具体库类型。

SMB 图片模型使用 `connectionId + normalizedPath + size + lastModified`，禁止在 Coil model、Room URI 或日志中包含密码。稳定键基于规范化相对路径生成；路径比较和去重必须同时覆盖 Windows/Samba 的分隔符、大小写与 Unicode 差异。

连接和文件句柄统一由一个 session manager 管理：限定并发、空闲关闭、网络切换后失效重建，所有流和目录句柄必须可观测地关闭。不要把长生命周期 SMB 对象存入 Compose 状态或 Room。

## 5. 分阶段实施

### M0：基线与 Spike（P0）

- 固化当前 UI 修复、JVM 26/26、UI smoke 2/2 和 signed Release 验证基线。
- 准备不含私人数据的 SMB 测试共享：中文、空格、嵌套目录、空目录、损坏图片、GIF、大图和至少一个较大相册。
- 完成依赖对比并形成 ADR，锁定版本、许可证说明、R8 规则和 SMB 最低协议。

退出条件：真机 Debug/Release 均能以 SMB2/3 完成连接、枚举和读图，且无句柄泄漏或明文凭据日志。

### M1：协议解耦（P0）

- 新建协议无关的远程数据源接口及 HTTP 适配器。
- 将相册同步、连接检查、图片定位和错误映射迁移到 gateway。
- 保证现有 HTTP/mDNS 行为、缓存键和 Room 数据不变。
- 为 HTTP 适配器补契约测试，避免重构引入回归。

退出条件：不启用 SMB 时，现有双机 HTTP Smoke 与远程 JVM 测试结果不变。

### M2：SMB 数据与安全（P0）

- Room 升级到 v5，新增可空 `domain`、`root_path`；补 v4→v5 migration 与 schema。
- 扩展去重键为 `type + host + port + share + rootPath + username/domain`，避免同主机多个共享互相覆盖。
- 使用 `KeyStoreManager` 保存密码；新增、删除、导入和连接失败路径均验证凭据清理。
- 建立 SMB 错误分类：认证失败、主机不可达、共享不存在、权限不足、超时、协议不支持和未知错误。
- 日志只记录连接 ID、阶段、耗时、数量和错误类别；主机、共享、用户名、路径、文件名和密码按 Release 隐私规则处理。

退出条件：migration、去重、凭据生命周期、错误映射和日志脱敏测试通过。

### M3：SMB 扫描与图片读取（P0）

- 实现只读连接、共享挂载、可选子目录和递归目录枚举。
- 复用当前图片扩展名和元数据规则，将目录映射为相册、文件映射为远程图片缓存。
- 扫描采用 IO dispatcher、批量 Room 事务、可取消任务、超时和有界并发；不得阻塞主线程。
- 初次扫描显示进度，失败保留旧索引；手动刷新做增量 upsert/delete，不因单次网络错误清空缓存。
- 新增不含凭据的 `SmbImageModel` 与 Coil Fetcher；缓存键包含连接、路径、大小和修改时间。
- 缩略图首版在客户端解码并进入现有远程缓存；优先保证正确性，再按实测决定是否增加采样读取或预取。

退出条件：真实大仓库可完成扫描、相册分页、图片网格和全屏浏览；取消、离线、恢复均不崩溃且不破坏缓存。

### M4：添加与管理 UI（P0）

- “添加远程仓库”增加协议选择：remoPhoto 设备 / SMB 共享。
- SMB 表单包含主机、端口、共享名、子目录、用户名、域、密码和“测试连接”。
- 只有连接测试成功才允许保存；保存过程中保持原子性，失败回滚连接、仓库和凭据。
- 仓库卡片标识 SMB、连接状态、上次成功时间和可执行的重新认证/刷新/删除操作。
- 错误文案使用可操作原因，不展示服务器原始异常、路径或凭据。

退出条件：TalkBack 标签、键盘类型、密码显隐、加载/失败状态和返回行为符合现有 Material 3 交互；HTTP 添加流程无回归。

### M5：自动化、真机验收与文档（P0）

- JVM：路径规范化、稳定 ID、图片过滤、分页、错误映射、去重、协议路由和刷新差异计算。
- Instrumentation：Room v4→v5 migration、Keystore 存取/删除和协议选择 UI 状态。
- 单机无人值守 SMB Smoke：由开发机提供测试共享，脚本完成前置检查、安装、连接、同步、进入相册、打开图片、断线/恢复和结果汇总；断言基于 UI tree、数据库/日志状态和文件内容，不依赖截图。
- 兼容矩阵至少覆盖 Windows SMB3 与 Samba/NAS 中一种；最低 Android 10 做安装、连接、扫描和读图基础回归。
- 更新 README、功能规格、协议边界、测试用例、第三方许可证和 Release Notes。

退出条件：脚本最终只输出 PASS 或 FAIL，并在失败时给出阶段、错误类别和证据目录；Release 日志隐私、Crash/ANR 和 minified 构建通过。

## 6. MVP 验收清单

- 能添加同一局域网内的 SMB2/3 共享，密码不出现在数据库、备份、日志、UI tree 和图片地址中。
- 中文、空格和嵌套目录可稳定映射为相册；重复刷新不产生重复仓库、相册或图片。
- 相册分页、分类关联、图片网格、GIF/静态图和全屏浏览与 HTTP 远程仓库体验一致。
- 10,000+ 图片测试仓库的扫描可取消、可观察且不阻塞 UI；具体耗时只记录实测基线，不提前承诺。
- 错误密码、离线、共享移除和权限不足均给出明确状态；恢复后原仓库可刷新，不要求重新添加。
- 删除 SMB 仓库后清除索引、对应图片缓存和 Keystore 凭据，不影响其他本地或远程仓库。
- 原有本地 SAF 和 HTTP/mDNS Smoke 继续通过。

## 7. 风险与降级策略

| 风险 | 处理 |
| --- | --- |
| Android/R8 与 SMB 库不兼容 | M0 先验证 minified Release；不通过则更换候选，不在业务层打补丁堆积技术债 |
| SMB1 安全风险 | 配置和测试双重保证只协商 SMB2/3；不提供用户开启 SMB1 的入口 |
| NAS 差异、DFS、域认证复杂 | MVP 保证普通共享与 NTLM 用户认证；DFS、Kerberos 按实测拆到后续版本 |
| 大仓库扫描慢 | 批量写入、进度、取消、增量刷新和有界并发；首版不做实时监听 |
| 缩略图流量和内存压力 | 使用 Coil 磁盘缓存和采样解码；以真机数据决定预取策略 |
| 凭据或路径泄露 | Keystore、结构化脱敏日志、备份复验和 Release 隐私扫描作为发布门禁 |
| 当前分支同时含 UI 修复与 SMB | SMB 开工前先将已验证 UI 修复形成独立提交；SMB 各里程碑小提交，便于回退和定位 |

## 8. 建议执行顺序

按 `M0 → M1 → M2 → M3 → M4 → M5` 顺序推进。首个开发回合只完成 M0 和 M1：先锁定依赖并解除 HTTP 耦合，不同时改数据库、UI 和扫描逻辑。这样即使 SMB 依赖最终更换，已有远程功能也不会被绑死在某个第三方库上。
