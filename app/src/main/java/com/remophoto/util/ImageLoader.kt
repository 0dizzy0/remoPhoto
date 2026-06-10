package com.remophoto.util

import android.content.Context
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy

/**
 * Coil 图片加载器工厂
 *
 * 配置全局 Coil 单例：内存缓存、磁盘缓存、GIF/WebP 动图支持。
 */
object ImageLoaderFactory {

    /**
     * 创建全局 ImageLoader 实例（含 GIF/WebP 动图支持，用于全屏浏览）
     *
     * @param context Application Context
     */
    fun create(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            // 内存缓存（LRU），128MB
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizeBytes(Constants.MEMORY_CACHE_SIZE)
                    .build()
            }
            // 磁盘缓存（缩略图 + 原图），200MB
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
            // 跨 fade 动画（300ms）
            .crossfade(300)
            // 默认启用磁盘缓存
            .diskCachePolicy(CachePolicy.ENABLED)
            // 内存缓存策略
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    /**
     * 创建缩略图专用 ImageLoader（不含动图解码，提升列表滚动流畅度）
     *
     * 与全局 loader 共享同一磁盘缓存目录。
     */
    fun createThumbnailLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizeBytes(Constants.MEMORY_CACHE_SIZE / 2) // 缩略图用一半内存
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(Constants.THUMBNAIL_CACHE_SIZE.toLong())
                    .build()
            }
            // 不添加 GIF/ImageDecoder 解码器 ← 关键：缩略图不播放动图
            .crossfade(200)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }
}
