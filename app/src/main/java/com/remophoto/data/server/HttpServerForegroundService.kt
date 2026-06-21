package com.remophoto.data.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.PowerManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.remophoto.MainActivity
import com.remophoto.R
import com.remophoto.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * HTTP Server 前台 Service
 *
 * 保持 HTTP Server 在后台持续运行，防止被系统杀死。
 * 通知栏显示"远程服务运行中"及当前连接地址。
 */
class HttpServerForegroundService : Service() {

    companion object {
        private const val TAG = "HttpServerForegroundService"
        private const val CHANNEL_ID = "remote_server"
        private const val CHANNEL_NAME = "远程服务"
        private const val NOTIFICATION_ID = 2001
        const val EXTRA_PORT = "port"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val ACTION_STOP = "com.remophoto.action.STOP_SERVER"

        private val _runtimeRunning = MutableStateFlow(false)
        val runtimeRunning: StateFlow<Boolean> = _runtimeRunning.asStateFlow()

        private val _runtimeAddress = MutableStateFlow<String?>(null)
        val runtimeAddress: StateFlow<String?> = _runtimeAddress.asStateFlow()
    }

    private val scope = MainScope()
    private var serverManager: HttpServerManager? = null
    private var mdnsRegistrar: MdnsRegistrar? = null
    private var wifiLockManager: WifiLockManager? = null
    private var startJob: Job? = null
    private val recoveryMutex = Mutex()
    private var configuredPort = 8080
    private var configuredDeviceName = ""
    private var lastLanIp: String? = null
    private lateinit var connectivityManager: ConnectivityManager
    private var callbacksRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleRecovery("network_available")
        override fun onLost(network: Network) {
            AppLogger.w(TAG, "默认网络丢失")
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) scheduleRecovery("screen_on")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        AppLogger.d(TAG, "Service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_STOP -> {
                stopServer()
            }
            else -> {
                val port = intent?.getIntExtra(EXTRA_PORT, 8080) ?: 8080
                val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: ""
                startServer(port, deviceName)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterRecoveryCallbacks()
        stopServer()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLogger.w(TAG, "最近任务被移除，重新确认远程前台服务")
        val restart = Intent(applicationContext, HttpServerForegroundService::class.java).apply {
            putExtra(EXTRA_PORT, configuredPort)
            putExtra(EXTRA_DEVICE_NAME, configuredDeviceName)
        }
        try {
            ContextCompat.startForegroundService(applicationContext, restart)
        } catch (e: Exception) {
            AppLogger.e(TAG, "任务移除后重启远程服务失败", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startServer(port: Int, deviceName: String) {
        configuredPort = port
        configuredDeviceName = deviceName
        // Settings 页面重建、Application 恢复和 START_STICKY 可能重复发送启动命令。
        // 重复创建 NanoHTTPD 会 EADDRINUSE，并使旧 HTTP 线程与 mDNS 生命周期脱节。
        if (serverManager?.isRunning() == true) {
            AppLogger.i(TAG, "远程服务已运行，忽略重复启动: port=$port")
            return
        }
        if (startJob?.isActive == true) {
            AppLogger.i(TAG, "远程服务正在启动，忽略重复启动: port=$port")
            return
        }

        // 立即调用 startForeground，避免 ANR（必须在 5s 内调用）
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val startingNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("remoPhoto 远程服务")
            .setContentText("正在启动...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(NOTIFICATION_ID, startingNotification)

        // 后台启动 HTTP Server + mDNS 注册
        startJob = scope.launch {
            try {
                AppLogger.i(TAG, "═════ 启动远程服务 ═════")
                AppLogger.i(TAG, "[步骤1/4] 创建 HttpServerManager...")
                val mgr = HttpServerManager(this@HttpServerForegroundService)

                AppLogger.i(TAG, "[步骤2/4] 启动 HTTP Server (port=$port, deviceName=$deviceName)...")
                val addr = withContext(Dispatchers.IO) {
                    mgr.start(port, deviceName)
                }

                if (addr != null) {
                    // 仅在启动成功后替换引用，失败不能丢失已有实例。
                    serverManager = mgr
                    _runtimeRunning.value = true
                    _runtimeAddress.value = addr
                    lastLanIp = addr.substringBeforeLast(':')
                    AppLogger.i(TAG, "[步骤2/4] ✅ HTTP Server 启动成功: $addr")

                    // 注册 mDNS 服务 + 获取 MulticastLock
                    try {
                        AppLogger.i(TAG, "[步骤3/4] 获取 WiFi 锁...")
                        val wlm = WifiLockManager(this@HttpServerForegroundService)
                        wifiLockManager = wlm
                        wlm.acquireMulticastLock()
                        wlm.acquireWifiLock()
                        wlm.acquireWakeLock()
                        AppLogger.i(TAG, "[步骤3/4] ✅ WiFi 锁已获取")

                        AppLogger.i(TAG, "[步骤4/4] 注册 mDNS 服务...")
                        val registrar = MdnsRegistrar(this@HttpServerForegroundService)
                        mdnsRegistrar = registrar
                        val registered = withContext(Dispatchers.IO) {
                            registrar.register(port, deviceName.ifEmpty { "remoPhoto" })
                        }
                        if (registered) {
                            AppLogger.i(TAG, "[步骤4/4] ✅ mDNS 注册成功")
                        } else {
                            AppLogger.w(TAG, "[步骤4/4] ⚠️ mDNS 注册失败（HTTP Server 仍在运行，可手动 IP 连接）")
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "[步骤3-4] ❌ mDNS 注册异常", e)
                    }

                    // 更新通知为运行状态
                    val stopIntent = PendingIntent.getService(
                        this@HttpServerForegroundService, 1,
                        Intent(this@HttpServerForegroundService, HttpServerForegroundService::class.java).apply {
                            action = ACTION_STOP
                        },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    val runningNotification = NotificationCompat.Builder(this@HttpServerForegroundService, CHANNEL_ID)
                        .setContentTitle("remoPhoto 远程服务运行中")
                        .setContentText("http://$addr  — 点击管理")
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setOngoing(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setContentIntent(pendingIntent)
                        .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
                        .build()
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFICATION_ID, runningNotification)
                    registerRecoveryCallbacks()

                    AppLogger.i(TAG, "前台 Service 已启动: http://$addr (mDNS registered)")
                } else {
                    AppLogger.w(TAG, "HTTP Server 启动失败，停止 Service")
                    _runtimeRunning.value = false
                    _runtimeAddress.value = null
                    stopSelf()
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Service startServer 异常", e)
                _runtimeRunning.value = false
                _runtimeAddress.value = null
                stopSelf()
            }
        }
    }

    private fun stopServer() {
        AppLogger.i(TAG, "═════ 停止远程服务 ═════")
        startJob?.cancel()
        try {
            AppLogger.d(TAG, "停止 HTTP Server...")
            serverManager?.stop()
            serverManager = null
        } catch (_: Exception) {}
        try {
            AppLogger.d(TAG, "注销 mDNS 服务...")
            mdnsRegistrar?.unregister()
            mdnsRegistrar = null
        } catch (_: Exception) {}
        try {
            AppLogger.d(TAG, "释放 WiFi 锁...")
            wifiLockManager?.releaseAll()
            wifiLockManager = null
        } catch (_: Exception) {}
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        _runtimeRunning.value = false
        _runtimeAddress.value = null
        lastLanIp = null
        AppLogger.i(TAG, "前台 Service 已停止")
    }

    private fun registerRecoveryCallbacks() {
        if (callbacksRegistered) return
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION") registerReceiver(screenReceiver, filter)
            }
            callbacksRegistered = true
            AppLogger.i(TAG, "网络与屏幕唤醒自愈监听已注册")
        } catch (e: Exception) {
            AppLogger.e(TAG, "注册自愈监听失败", e)
        }
    }

    private fun unregisterRecoveryCallbacks() {
        if (!callbacksRegistered) return
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        callbacksRegistered = false
    }

    private fun scheduleRecovery(reason: String) {
        scope.launch {
            recoveryMutex.withLock {
                val manager = serverManager ?: return@withLock
                val currentIp = wifiLockManager?.getLanIp()
                if (currentIp == null) {
                    AppLogger.w(TAG, "自愈延后: reason=$reason, LAN IP 不可用")
                    return@withLock
                }
                val needsHttpRebind = !manager.isRunning() || currentIp != lastLanIp
                AppLogger.i(
                    TAG,
                    "自愈检查: reason=$reason, oldIp=$lastLanIp, newIp=$currentIp, rebind=$needsHttpRebind"
                )
                if (needsHttpRebind) {
                    val address = withContext(Dispatchers.IO) {
                        manager.restart(configuredPort, configuredDeviceName)
                    } ?: return@withLock
                    lastLanIp = address.substringBeforeLast(':')
                    _runtimeAddress.value = address
                }
                withContext(Dispatchers.IO) {
                    mdnsRegistrar?.unregister()
                    mdnsRegistrar?.register(configuredPort, configuredDeviceName.ifEmpty { "remoPhoto" })
                }
                AppLogger.i(TAG, "mDNS 已重新注册: reason=$reason, httpRebind=$needsHttpRebind")
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
        ).apply { description = "remoPhoto 远程图片服务运行状态" }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
