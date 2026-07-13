# 第三方依赖声明

更新时间：2026-07-13

本文件记录 SMB 功能直接引入的运行时依赖链。AndroidX、Kotlin、Compose、Room、Coil 等既有依赖继续由其各自许可证约束，不因本清单而改变。

| 组件 | 版本 | 引入关系 | 许可证 |
| --- | --- | --- | --- |
| `com.hierynomus:smbj` | `0.14.0` | 应用直接依赖 | Apache License 2.0 |
| `com.hierynomus:asn-one` | `0.6.0` | SMBJ 间接依赖 | Apache License 2.0 |
| `net.engio:mbassador` | `1.3.0` | SMBJ 间接依赖 | MIT License |
| `org.slf4j:slf4j-api` | `2.0.9` | SMBJ 间接依赖，按 Gradle 冲突解析结果 | MIT License |
| `org.bouncycastle:bcprov-jdk18on` | `1.84` | SMBJ 间接依赖；应用安全约束覆盖 `1.79` | Bouncy Castle License（MIT 风格） |

许可证来源：

- SMBJ 与 asn-one：发布 POM 中声明 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)。
- mbassador：发布 POM 和上游仓库声明 [MIT License](https://github.com/bennidi/mbassador/blob/master/LICENSE)。
- SLF4J：[MIT License](https://www.slf4j.org/license.html)。
- Bouncy Castle：[Bouncy Castle License](https://downloads.bouncycastle.org/java/docs/bcprov-jdk18on-javadoc/org/bouncycastle/LICENSE.html)。

供应链说明：

- GitHub Advisory [GHSA-c3fc-8qff-9hwx / CVE-2026-0636](https://github.com/advisories/GHSA-c3fc-8qff-9hwx) 标记 `bcprov-jdk18on >= 1.74, < 1.84` 受影响，修复版为 `1.84`。
- 应用通过 Gradle dependency constraint 固定 `bcprov-jdk18on:1.84`；`dependencyInsight` 必须显示 SMBJ 请求的 `1.79 -> 1.84`，避免未来解析回退到受影响版本。
- 该约束不代表项目已完成所有既有 Android 依赖的全量 SBOM/漏洞扫描；正式发布仍应以锁定依赖图执行一次完整供应链扫描。
