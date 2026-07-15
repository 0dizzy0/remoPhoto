package com.remophoto

import android.app.Application
import coil.Coil
import android.content.Intent
import androidx.core.content.ContextCompat
import com.remophoto.data.server.HttpServerForegroundService
import com.remophoto.di.DependencyContainer
import com.remophoto.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

        // 导入/设备恢复不会携带 Keystore 凭据；启动时禁止以空密码尝试 SMB。
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                dependencyContainer.remoteRepositoryLifecycleService.recoverMissingCredentials()
            }.onFailure { error ->
                AppLogger.e(
                    "RemoPhotoApp",
                    "SMB 凭据恢复检查失败: category=${error.javaClass.simpleName}",
                )
            }
        }

        // 远程服务属于应用级能力，不能依赖用户是否进入设置页面。
        ensureRemoteServiceRunning("application_create")
    }

    /** 幂等自愈入口：Service 内部同时具有运行中与启动中去重保护。 */
    fun ensureRemoteServiceRunning(reason: String) {
        applicationScope.launch {
            val settings = dependencyContainer.settingsRepository
            if (settings.httpServerEnabled.first()) {
                val port = settings.httpServerPort.first()
                val deviceName = settings.deviceName.first()
                AppLogger.i("RemoPhotoApp", "远程服务自愈检查: reason=$reason, port=$port, name=$deviceName")
                val intent = Intent(this@RemoPhotoApp, HttpServerForegroundService::class.java).apply {
                    putExtra(HttpServerForegroundService.EXTRA_PORT, port)
                    putExtra(HttpServerForegroundService.EXTRA_DEVICE_NAME, deviceName)
                }
                try {
                    ContextCompat.startForegroundService(this@RemoPhotoApp, intent)
                } catch (e: Exception) {
                    // Android 12+ 在纯后台拉起进程时可能限制前台服务；下次前台启动再恢复。
                    AppLogger.e("RemoPhotoApp", "应用启动恢复远程服务失败", e)
                }
            } else {
                AppLogger.d("RemoPhotoApp", "远程服务自愈跳过: reason=$reason, enabled=false")
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_UI_HIDDEN) {
            AppLogger.i("RemoPhotoApp", "应用进入后台，执行远程服务保活检查")
            ensureRemoteServiceRunning("ui_hidden")
        }
    }

    companion object {
        lateinit var instance: RemoPhotoApp
            private set
    }
}
