# remoPhoto Alpha 发布检查清单

> 本文件是单次发布的执行模板。发布范围和门禁定义以[首个 Alpha Release 计划](08_首个AlphaRelease计划.md)为准。

## 发布信息

| 项目 | 记录 |
| --- | --- |
| versionName |  |
| versionCode |  |
| Commit |  |
| Tag |  |
| 发布日期 |  |
| 测试记录 |  |

## 代码与工作区

- [ ] 版本号已递增，且与 Tag、Release Notes 一致。
- [ ] `git status --short` 仅包含明确准备提交的文件。
- [ ] 本地缓存、日志、签名文件、APK/AAB 未进入提交。
- [ ] Room schema 已导出并纳入版本控制，或已记录接受该风险的理由。
- [ ] Release 日志等级和敏感信息输出已复核。

## 构建与签名

- [ ] `:app:testDebugUnitTest` 通过。
- [ ] `:app:lintDebug` 通过并输出 `No issues found`。
- [ ] `:app:assembleRelease` 通过。
- [ ] 生成 signed APK/AAB。
- [ ] `apksigner verify --verbose` 或等价签名校验通过。
- [ ] APK 可在目标真机安装。
- [ ] `dumpsys package com.remophoto` 显示正确版本。
- [ ] 已生成并记录 SHA-256。

## 核心回归

- [ ] 执行 `docs/testing/07_测试用例.md` 第 17 节最低 Smoke；首次公开 Alpha 补远程和备份模块包。
- [ ] 覆盖安装和冷启动无崩溃。
- [ ] 本地仓库添加、扫描、重扫、删除可用。
- [ ] 相册分页、排序、模糊定位可用。
- [ ] 图片网格和全屏浏览可用。
- [ ] 分类和设置持久化可用。
- [ ] 两台设备完成远程发现、连接、分页、离线和恢复验证。
- [ ] 数据库导出和有效/非法导入至少各验证一次；失败回滚已验证，或发布计划明确记录接受该残留风险。
- [ ] Crash buffer 无新增 `FATAL EXCEPTION`。
- [ ] 关键流程日志可定位且已脱敏。

## Release 内容

- [ ] Release Notes 明确标注 Alpha。
- [ ] 列出已验证设备、Android 版本和仓库规模。
- [ ] 列出 SMB、明文 HTTP、后台限制和自动化覆盖不足等已知限制。
- [ ] 明确远程服务仅用于可信局域网。
- [ ] 附件只包含签名产物和 SHA-256，不包含密钥。
- [ ] 开源许可证状态已明确。
- [ ] AI 辅助开发、Skills、MCP 状态和人工验收责任已公开说明。

## 最终确认

- [ ] 测试负责人确认。
- [ ] 发布负责人确认。
- [ ] Tag 指向已通过验证的 Commit。
- [ ] 推送后检查 GitHub Release 页面、附件和校验值。
