# ADR-005：Android SMB 客户端库选型

状态：**待真机验证（暂不批准进入正式业务模块）**

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
  - `org.bouncycastle:bcprov-jdk18on:1.79`
  - `net.engio:mbassador:1.3.0`
  - `com.hierynomus:asn-one:0.6.0`
- 独立 `:spikes:smbj-android` 模块已通过 API 29 的 Debug 编译、D8 打包和 minified Release/R8 构建。
- 首次 minified Release 暴露 Android 缺失 `javax.el` 与 `org.ietf.jgss`。它们分别对应未使用的 mbassador EL 过滤和不在 MVP 范围内的 Kerberos/SPNEGO；采用 R8 生成的逐类 `-dontwarn` 后构建通过，没有使用宽泛的 `-dontwarn **`。
- Spike 调用链实际引用 `SMBClient`、NTLM `AuthenticationContext`、connection、session、`DiskShare.list()` 和各级 `use/close`，避免 R8 因无引用直接裁掉 SMBJ。

## 当前决定

保留 SMBJ `0.14.0` 为首选，但在以下真机门禁完成前，**不把 SMBJ 添加到正式 `app` 依赖，也不开始 M2/M3**：

1. API 29 和一台较新 Android 真机完成 Debug 与 minified Release 安装、冷启动、NTLM 认证、中文目录枚举和图片流读取。
2. Windows SMB3 与 Samba/NAS 均完成互通，并记录协商 dialect、签名和加密状态。
3. 错误密码、SMB1-only、离线、超时、取消和网络恢复行为符合统一错误分类。
4. 连续打开至少 100 张图片后无持续增长的连接、session、tree、文件或线程资源。
5. Release 日志、UI tree、缓存和证据文件中无密码、端点、共享名、用户名或远程路径。

## 已知风险

- 当前只证明 Android 工具链和 R8 可构建，不能据此推断 SMB3 互通或运行时稳定。
- `org.ietf.jgss` 在 Android 缺失意味着当前方案不能支持 Kerberos；这与 MVP 排除项一致。
- Bouncy Castle 是主要包体来源之一；正式接入前需以 `app` 的 before/after APK Analyzer 数据记录真实增量。
- Release R8 规则需要随 SMBJ 升级重新生成和复核，不能永久复制旧规则。

## 回退

若任一必需真机矩阵无法通过且无上游可接受修复，则删除独立 Spike，转为 jcifs-ng 等价验证；不得在正式业务层增加服务端特判或启用 SMB1。
