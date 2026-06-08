package com.remophoto

import android.app.Application
import com.remophoto.di.DependencyContainer

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
    }

    companion object {
        lateinit var instance: RemoPhotoApp
            private set
    }
}
