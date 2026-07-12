# M0 Spike 必须让 R8 分析并保留真实 SMBJ 调用链，不能只验证依赖可解析。
-keep class com.remophoto.smbjspike.** { *; }

# SMBJ 运行时通过 SLF4J 记录内部诊断；Spike 使用应用自己的结构化日志作为验收证据。
-dontwarn org.slf4j.impl.**

# Android 不提供以下 Java SE 可选 API；MVP 不使用 mbassador EL 过滤或 Kerberos/SPNEGO。
# 规则来自本模块 R8 生成的 missing_rules.txt，保持逐类收敛，避免掩盖其他缺失依赖。
-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid
