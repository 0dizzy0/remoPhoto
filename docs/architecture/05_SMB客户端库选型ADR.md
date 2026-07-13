# ADR-005：Android SMB 客户端库选型

状态：**有条件接受（已进入正式模块，M5 兼容矩阵尚未收口）**

日期：2026-07-12

## 背景

remoPhoto `0.2.0` 计划增加可信局域网内的 SMB2/3 只读仓库。客户端库必须在 Android 10/API 29、Debug 和 minified Release 下工作，并满足 SMB1 禁用、NTLM 普通账号、可取消读取和严格资源释放要求。

## 候选

| 候选 | 版本 | 许可证 | 初步结论 |
| --- | --- | --- | --- |
| SMBJ | `0.14.0` | Apache-2.0 | 首选；进入 Android Spike |
| jcifs-ng | `2.1.10` | LGPL-2.1 | 备选；只有 SMBJ 真机门禁失败后才执行等价 Spike |

## 已获得证据

- 阿里云 `public` 镜像与 Maven Central 上的 SMBJ `0.14.0` POM 均返回 HTTP 200。
- Gradle `releaseRuntimeClasspath` 成功解析 SMBJ `0.14.0`，传递依赖为：
  - `org.slf4j:slf4j-api:2.0.9`
  - `org.bouncycastle:bcprov-jdk18on:1.84`（由应用约束覆盖 SMBJ 请求的 `1.79`）
  - `net.engio:mbassador:1.3.0`
  - `com.hierynomus:asn-one:0.6.0`
- 独立 `:spikes:smbj-android` 模块已通过 API 29 的 Debug 编译、D8 打包和 minified Release/R8 构建。
- 首次 minified Release 暴露 Android 缺失 `javax.el` 与 `org.ietf.jgss`。它们分别对应未使用的 mbassador EL 过滤和不在 MVP 范围内的 Kerberos/SPNEGO；采用 R8 生成的逐类 `-dontwarn` 后构建通过，没有使用宽泛的 `-dontwarn **`。
- Spike 调用链实际引用 `SMBClient`、NTLM `AuthenticationContext`、connection、session、`DiskShare.list()` 和各级 `use/close`，避免 R8 因无引用直接裁掉 SMBJ。
- 正式模块已完成 Android 12/16 instrumentation、Android 12 到 Windows SMB3 的认证、中文目录枚举、8448/155794 张图片扫描、静态图/GIF 读取、断线恢复、凭据替换和仓库删除回归。
- 2026-07-13 供应链复核发现 SMBJ 间接解析的 Bouncy Castle `1.79` 受 CVE-2026-0636 影响；应用使用 Gradle constraint 固定到公告修复版 `1.84`。Debug、AndroidTest、minified Release/R8、API 31/36 instrumentation 与 Windows SMB 刷新均在升级后重新通过。

## 当前决定

保留 SMBJ `0.14.0` 作为正式 SMB2/3 客户端，不因 Bouncy Castle 修复升级 SMBJ。M2～M4 已完成，以下未覆盖项继续作为 M5 上线门禁：

1. API 29 和一台较新 Android 真机完成 Debug 与 minified Release 安装、冷启动、NTLM 认证、中文目录枚举和图片流读取。
2. Windows SMB3 与 Samba/NAS 均完成互通，并记录协商 dialect、签名和加密状态。
3. 错误密码、SMB1-only、离线、超时、取消和网络恢复行为符合统一错误分类。
4. 连续打开至少 100 张图片后无持续增长的连接、session、tree、文件或线程资源（HTTP/图片解码压力已通过；SMB 专项仍随完整矩阵复核）。
5. Release 日志、UI tree、缓存和证据文件中无密码、端点、共享名、用户名或远程路径（当前已通过，最终候选仍需重跑）。

## 已知风险

- Windows SMB3、API 31 和 API 36 已有运行时证据；API 29 与 Samba/NAS 尚未完成，不能把现有结果外推到未测矩阵。
- `org.ietf.jgss` 在 Android 缺失意味着当前方案不能支持 Kerberos；这与 MVP 排除项一致。
- Bouncy Castle 是主要包体来源之一；当前 Release APK 为 5,802,452 字节，后续升级继续记录包体和 R8 结果。
- Release R8 规则需要随 SMBJ 升级重新生成和复核，不能永久复制旧规则。
- SMB 签名策略和 SMBJ 上游断线线程 issue 按维护者决定暂不作为当前门禁；等待上游补丁后单独升级和回归，不在业务层加入侵入式补丁。

## 回退

若任一必需真机矩阵无法通过且无上游可接受修复，则删除独立 Spike，转为 jcifs-ng 等价验证；不得在正式业务层增加服务端特判或启用 SMB1。
