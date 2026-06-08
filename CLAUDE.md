1、尽量使用中文回答

2、下载的依赖和软件尽量装在开发目录下，不要默认装在c盘

3、遇到下载软件或依赖速度缓慢时，尝试使用国内的镜像源

4、设置镜像源时，确保可达性，同时确认目标版本在源仓库上存在

---

## 项目状态

| 项目 | 详情 |
|------|------|
| 包名 | `com.remophoto` |
| 当前阶段 | Phase 0 ✅ → Phase 1 进行中 |
| minSdk / targetSdk | 29 / 35 |
| 架构 | MVVM + Repository，手动 DI（`DependencyContainer`） |
| 数据库 | Room 2.6.1 + KSP，7 张表 |
| 图片加载 | Coil 2.7.0（GIF/WebP 支持） |
| UI | Jetpack Compose + Material 3 + Navigation Compose |
| Kotlin / Gradle / AGP | 2.0.21 / 8.7 / 8.5.2 |
| JDK | 17（JetBrains JBR 21.0.10） |
| 镜像 | 阿里云 Maven + 腾讯云 Gradle 分发 |
| Android Studio | `E:\ai_work\android studio\` |
| Android SDK | `E:\ai_work\android_sdk` |
| Gradle Home | `E:\ai_work\gradle` |

### Phase 0 完成情况（2026-06-08）

- ✅ P0-01 ~ P0-07 全部完成
- ✅ 编译通过，App 已安装到真机验证
- ✅ Git 已提交（3 个 commit），Gradle Wrapper 已就绪
- ⚠️ Room `exportSchema = true` 未配 `schemaLocation`（KSP warning，非阻塞）
- ⚠️ 主题切换 API 已就绪，UI 切换入口留待 Phase 2

