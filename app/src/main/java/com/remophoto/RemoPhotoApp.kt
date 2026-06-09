package com.remophoto

import android.app.Application
import coil.Coil
import com.remophoto.di.DependencyContainer
import com.remophoto.util.AppLogger

/**
 * remoPhoto Application 类
 *
 * 负责全局初始化：
 * - Room 数据库单例
 * - Coil 图片加载器配置
 * - 依赖注入容器
 * - 全局设置初始化
 */
class RemoPhotoApp : Application() {

    /** Room 数据库实例（懒加载单例） */
    val database by lazy {
        com.remophoto.data.local.AppDatabase.getInstance(this)
    }

    /** 依赖注入容器（懒加载） */
    lateinit var dependencyContainer: DependencyContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        dependencyContainer = DependencyContainer(this)

        // 初始化日志系统
        AppLogger.init(this)

        // 设置 Coil 全局 ImageLoader（含 GIF 解码器）
        Coil.setImageLoader(dependencyContainer.imageLoader)
        AppLogger.i("RemoPhotoApp", "Coil 全局 ImageLoader 已设置（GIF/WebP 动图支持）")
    }

    companion object {
        lateinit var instance: RemoPhotoApp
            private set
    }
}
