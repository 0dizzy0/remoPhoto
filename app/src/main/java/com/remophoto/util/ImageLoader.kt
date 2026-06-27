package com.remophoto.util

import android.content.Context
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.remophoto.data.remote.RemoteImageFetcher

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
            // GIF 动图解码 + 远程图片加载
            .components {
                add(GifDecoder.Factory())
                add(RemoteImageFetcher.Factory())
                // Android 9+ 内置 ImageDecoder 支持 WebP 动图
                add(ImageDecoderDecoder.Factory())
            }
            // 跨 fade 动画（150ms，降低视觉延迟）
            .crossfade(150)
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
                    .directory(context.cacheDir.resolve("thumb_cache"))
                    .maxSizeBytes((Constants.THUMBNAIL_CACHE_SIZE / 2).toLong())
                    .build()
            }
            // 不添加 GIF/ImageDecoder 解码器 ← 关键：缩略图不播放动图
            .crossfade(0)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    /**
     * Phase 4: 远程缩略图专用 ImageLoader
     *
     * 独立缓存目录 remote_thumb_cache（100MB），含 RemoteImageFetcher。
     * 不加载动图，优先使用磁盘缓存。
     */
    fun createRemoteThumbnailLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizeBytes(Constants.MEMORY_CACHE_SIZE / 2) // 64MB
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("remote_thumb_cache"))
                    .maxSizeBytes(100 * 1024 * 1024L) // 100MB
                    .build()
            }
            .components {
                add(RemoteImageFetcher.Factory())
            }
            .crossfade(0) // 缩略图无动画
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    /**
     * Phase 4: 远程原图专用 ImageLoader
     *
     * 独立缓存目录 remote_image_cache（300MB），含 RemoteImageFetcher + GIF 解码。
     * 用于全屏浏览远程图片。
     */
    fun createRemoteImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizeBytes(Constants.MEMORY_CACHE_SIZE) // 128MB
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("remote_image_cache"))
                    .maxSizeBytes(300 * 1024 * 1024L) // 300MB
                    .build()
            }
            .components {
                add(RemoteImageFetcher.Factory())
                add(GifDecoder.Factory())
                add(ImageDecoderDecoder.Factory())
            }
            .crossfade(150)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }
}
