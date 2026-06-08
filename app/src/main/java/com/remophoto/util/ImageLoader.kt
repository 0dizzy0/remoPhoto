package com.remophoto.util

import android.content.Context
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.util.DebugLogger

/**
 * Coil 图片加载器工厂
 *
 * 配置全局 Coil 单例：内存缓存、磁盘缓存、GIF/WebP 动图支持。
 */
object ImageLoaderFactory {

    /**
     * 创建全局 ImageLoader 实例
     *
     * @param context Application Context
     */
    fun create(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            // 内存缓存（LRU）
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizeBytes(Constants.MEMORY_CACHE_SIZE)
                    .build()
            }
            // 磁盘缓存（缩略图）
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(Constants.THUMBNAIL_CACHE_SIZE.toLong())
                    .build()
            }
            // GIF 动图解码
            .components {
                add(GifDecoder.Factory())
                // Android 9+ 内置 ImageDecoder 支持 WebP 动图
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                }
            }
            // 跨 fade 动画
            .crossfade(300)
            // 调试日志（Release 时移除）
            // .logger(DebugLogger())
            .build()
    }
}
