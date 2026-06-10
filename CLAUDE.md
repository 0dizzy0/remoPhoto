1、尽量使用中文回答

2、下载的依赖和软件尽量装在开发目录下，不要默认装在c盘

3、遇到下载软件或依赖速度缓慢时，尝试使用国内的镜像源

4、设置镜像源时，确保可达性，同时确认目标版本在源仓库上存在

5、关键步骤需要加入可观测的日志，以供后续分析定位问题

---

## 项目状态

| 项目 | 详情 |
|------|------|
| 包名 | `com.remophoto` |
| 当前阶段 | Phase 0 ✅ → Phase 1 ✅ → Phase 2 ✅ → Phase 3 ✅ → Phase 4 待开始 |
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
- ✅ 主题切换 UI 入口已在 Phase 2 完成

### Phase 2 完成情况（2026-06-09）✅ 已收敛

**P2-01~P2-05 手势交互:** 点击区域翻页、音量键翻页、鼠标滚轮翻页、长按菜单、平移边界约束
**P2-06~P2-09 分类管理:** CategoryManager + 分类 UI + 树形筛选 + 多对多关联
**P2-10~P2-12 分页:** PageNavigator 智能页码 + 跳页弹窗
**P2-13~P2-16 播放:** SlideshowControl 间隔选择器 + 缩略图禁用动图（独立 loader）
**P2-17~P2-19 设置:** SettingsScreen + AlbumSettingsScreen + 主题切换接入
**P2-F01~F07 缺陷:** FocusRequester 崩溃、排序错位、Flow 阻塞、标签删除、分类筛选、状态残留

**新增文件:** CategoryManager, CategoryListScreen, CategoryViewModel, AlbumSettingsScreen, AlbumSettingsViewModel, file_paths.xml
**新增路由:** `categories`, `album_settings/{albumId}`, `album_list?categoryId=&categoryName=`

### Phase 3 完成情况（2026-06-10）✅

**P3-01 Material 3 排版系统:** Type.kt 完整 M3 Typography（display/headline/title/body/label），Color.kt 完整色槽（primary/secondary/tertiary/error + container 变体 + outline/inverse）
**P3-02 OLED 深色背景:** DarkModeType 枚举（AUTO/OLED/LCD），SettingsRepository 增加 darkModeType + highContrast 设置项，SettingsScreen 增加深色背景类型选择器
**P3-03 动画过渡:** NavGraph 页面转场 slide+fade（tween 300ms），AlbumListScreen AnimatedContent 子相册展开动画（expandVertically + fadeIn）
**P3-04 列表性能:** LazyColumn/LazyGrid 添加 contentType 提高复用效率，相册 > 20 时显示快速滚动条指示器（ScrollPositionIndicator）
**P3-05 图片加载:** ImageThumbnail INEXACT 精度解码，memoryCacheKey 优化缓存命中，ImageLoader 显式 CachePolicy.ENABLED
**P3-06 空错状态:** 新建 EmptyStateView（通用空状态组件）和 ErrorStateView（错误重试组件），替换 AlbumListScreen 和 GalleryScreen 内联空状态
**P3-07 启动屏:** SplashScreen 背景色改为纯黑 #000000
**P3-08 辅助功能:** 所有 IconButton/Text emoji 按钮添加 contentDescription/semantics，AlbumCard 封面 contentDescription 改为"相册封面：${name}"
**遗留项:** FileScanner 添加 coroutineContext.ensureActive() 取消支持；DatabaseExporter 完整导入/导出（ZIP+SAF+备份回滚）；SettingsScreen SAF launcher 集成

**新增文件:** Type.kt, EmptyStateView.kt, ErrorStateView.kt, DatabaseExporter.kt
**修改文件:** Theme.kt, Color.kt, SettingsRepository.kt, SettingsScreen.kt, SettingsViewModel.kt, MainActivity.kt, NavGraph.kt, AlbumListScreen.kt, GalleryScreen.kt, ImageLoader.kt, ImageThumbnail.kt, FileScanner.kt, AlbumCard.kt, colors.xml
**遗留 Phase 4:** 远程仓库（SMB+HTTP+mDNS）、视频播放、多用户权限管理

### Phase 3 迭代 — UI 增强 + 交互优化（2026-06-10）

**UI 调整 (P3-F05):**
- 双列网格模式移除蓝色封面文件名（调试信息不应展示）
- 相册名称 `maxLines` 1→2（单双列模式均扩展）
- 清理已无引用的 `extractFileName()` 函数

**分类筛选空状态区分 (P3-F06):**
- 全局无相册：「请先添加图片仓库」+「添加仓库」按钮
- 分类筛选为空：「暂无相册，请在主界面添加相册至此分类」（无操作按钮）
- 通过 `activeFilterCategoryName != null` 判断场景

**ViewModel 协程竞态修复 (P3-F07):**
- `loadAlbums()` 和 `loadAlbumsByCategory()` 的 `collect` 协程竞态导致筛选数据覆盖全量数据
- 修复：新增 `loadJob: Job?`，启动新加载前 `loadJob?.cancel()`

**长按多选 + 批量添加分类 (P3-F08):**
- AlbumCard 新增 `selected`/`selectionMode`/`onLongClick` 参数，`Card(onClick)` → `Modifier.combinedClickable`
- 多选模式下 Checkbox 叠加层 + primary 色边框
- AlbumListViewModel 新增 `selectionMode`/`selectedAlbumIds`/`allCategories` StateFlow
- 新增 `enterSelectionMode`/`exitSelectionMode`/`toggleSelection`/`loadAllCategories`/`addSelectedToCategory` 方法
- AlbumListScreen 多选模式 TopBar（已选X项 + 取消 + 添加到分类）
- 分类选择器 AlertDialog（LazyColumn + 颜色圆点 + 批量关联）

**缩略图可拖动快速滚动条 (P3-F09):**
- 新建 `DraggableScrollbar` 通用组件（24dp 触摸区，8dp 视觉轨道）
- `detectVerticalDragGestures` 支持拖拽快速定位
- `BoxWithConstraints` + `offset` 实现滑块实时跟随手指
- 颜色适配主题（`outline`/`primary`），自动隐藏（1.5s 淡出）
- GalleryScreen 网格/列表模式均集成（图片数 > 20 时显示）

**新增文件:** DraggableScrollbar.kt
**修改文件:** AlbumCard.kt, AlbumListScreen.kt, AlbumListViewModel.kt, GalleryScreen.kt
**修复 Bug:** P3-F05~F09
