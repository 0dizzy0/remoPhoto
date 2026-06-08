package com.remophoto.util

/**
 * 应用全局常量
 */
object Constants {

    /** 支持的图片文件扩展名（小写） */
    val SUPPORTED_IMAGE_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp"
    )

    /** 支持的视频文件扩展名（P3 远期） */
    val SUPPORTED_VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "ts"
    )

    /** 相册目录最大嵌套深度（超过则合并） */
    const val MAX_ALBUM_DEPTH = 3

    /** 默认每页相册数量 */
    const val DEFAULT_ALBUMS_PER_PAGE = 20

    /** 全屏浏览预加载：向后页数 */
    const val PRELOAD_FORWARD_PAGES = 5

    /** 全屏浏览预加载：向前页数 */
    const val PRELOAD_BACKWARD_PAGES = 2

    /** 双指缩放最小比例 */
    const val MIN_SCALE = 1f

    /** 双指缩放最大比例 */
    const val MAX_SCALE = 5f

    /** 默认自动播放间隔（毫秒） */
    const val DEFAULT_SLIDESHOW_INTERVAL_MS = 3000L

    /** 数据库批量事务每批最大条目 */
    const val DB_BATCH_SIZE = 500

    /** 缩略图磁盘缓存上限（字节） */
    const val THUMBNAIL_CACHE_SIZE = 200 * 1024 * 1024  // 200MB

    /** 内存缓存上限（字节） */
    const val MEMORY_CACHE_SIZE = 128 * 1024 * 1024  // 128MB

    /** 远程原图磁盘缓存上限（P3） */
    const val REMOTE_ORIGINAL_CACHE_SIZE = 500 * 1024 * 1024  // 500MB

    /** 远程 HTTP Server 默认端口 */
    const val REMOTE_HTTP_PORT = 8080

    /** mDNS 服务类型 */
    const val MDNS_SERVICE_TYPE = "_remophoto._tcp.local."

    /** 进度条自动隐藏延时（毫秒） */
    const val UI_AUTO_HIDE_DELAY_MS = 3000L
}
