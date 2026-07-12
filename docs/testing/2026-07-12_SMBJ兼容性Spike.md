# 2026-07-12 SMBJ Android 兼容性 Spike 执行记录

## 结论

**部分通过，真机门禁待执行。** 依赖源、版本解析、API 29 编译、Debug 打包和 minified Release/R8 已通过；当前 adb 无已连接设备，因此没有执行安装、认证、目录枚举、读图、断线恢复和资源压力测试。

## 环境与基线

| 项目 | 结果 |
| --- | --- |
| 分支 | `codex/smb-support` |
| 计划提交 | `8ee106b` |
| Gradle / JVM | Gradle 8.7 / JetBrains Runtime 21.0.10 |
| Android 配置 | compileSdk 35 / minSdk 29 / targetSdk 35 |
| 改动前 JVM | PASS，26 tests / 0 failures |
| 改动前 Lint | PASS，0 issue |
| 改动前 minified Release | PASS，4,211,399 bytes，SHA-256 `27fe590ad59cb92c2f0536f292decaab0c6025eb938cb99a31d57aba97138eff` |

## 仓库与依赖验证

| 检查 | 结果 |
| --- | --- |
| 阿里云 public 镜像 POM | HTTP 200，558 ms |
| Maven Central POM | HTTP 200，717 ms |
| 解析版本 | `com.hierynomus:smbj:0.14.0` |
| SMBJ JAR | 607,813 bytes，SHA-256 `f5cd63af2f343bfa13d41dbf50cfd251d2b99c4118fbe82b0e94ee0f93d1cc14` |
| Debug Spike APK | 9,150,205 bytes，SHA-256 `b7c33c1aac678bcb9b83552fad4851d14517659033280af5ee327b45ffa259c5` |
| minified Release Spike APK | 1,496,858 bytes，SHA-256 `ac9a97141b995ad35d23075dcadf87c7ee3c09e6e008e58ceb50cb0f756f62ef` |

Spike APK 是独立最小应用，其大小不能直接当作正式 app 增量；正式接入前另做 app before/after 对比。

## 执行过程

1. 首次构建因探针错误引用 autofill 常量失败，修正为 `View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`。
2. 第二次 Debug 构建通过；Release R8 报缺少 `javax.el.*` 与 `org.ietf.jgss.*`。
3. 采用 R8 `missing_rules.txt` 生成的逐类规则，明确对应非 MVP 的 EL/Kerberos 路径。
4. 第三次 minified Release 构建通过。
5. `adb devices -l` 返回空设备列表，停止真机步骤，没有伪造 PASS 结论。

## 待完成门禁

- [ ] API 29 真机 Debug 安装、冷启动和根目录枚举。
- [ ] API 29 真机 minified Release 安装与相同流程。
- [ ] 较新 Android 真机重复上述流程。
- [ ] Windows SMB3 与 Samba/NAS 认证、中文/特殊字符/长路径、大图和 GIF。
- [ ] 错误密码、SMB1-only、权限不足、离线、超时、取消和恢复。
- [ ] 连续 100 张读图及 10,000+ 文件枚举的资源、内存、Crash/ANR 检查。
- [ ] Release 日志、UI tree 和证据目录隐私扫描。

在以上门禁完成前，ADR 保持“待真机验证”，M2/M3 不得开始。
