package com.remophoto.util

import android.content.Context
import android.util.Log
import com.remophoto.BuildConfig
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile

/**
 * 应用日志系统
 *
 * 功能：
 * - 同时输出到 Logcat 和文件
 * - 支持 DEBUG / INFO / WARN / ERROR 四个级别
 * - 日志文件按日滚动（app_yyyyMMdd.log）
 * - 缓冲写入（批量 flush）减少 IO
 * - 线程安全
 *
 * 用法：
 * ```
 * // 在 Application.onCreate() 中初始化
 * AppLogger.init(context)
 *
 * // 在任意位置记录日志
 * AppLogger.i("AlbumCover", "自动选取封面: albumId=$albumId")
 * AppLogger.e("Thumbnail", "加载失败", throwable)
 * ```
 *
 * 日志目录：<应用外部文件目录>/remoPhoto/logs/
 */
object AppLogger {

    /** 日志级别 */
    enum class Level(val emoji: String, val priority: Int) {
        DEBUG("🔍", Log.DEBUG),
        INFO("ℹ️", Log.INFO),
        WARN("⚠️", Log.WARN),
        ERROR("❌", Log.ERROR)
    }

    /** 最低记录级别（低于此级别的日志将被忽略） */
    @Volatile
    var minLevel: Level = if (BuildConfig.DEBUG) Level.DEBUG else Level.INFO

    /** 是否启用文件写入 */
    @Volatile
    var fileLogEnabled: Boolean = true

    /** 是否启用 Logcat 输出 */
    @Volatile
    var logcatEnabled: Boolean = BuildConfig.DEBUG

    /** 每多少条日志 flush 一次到文件 */
    private const val FLUSH_INTERVAL = 50

    /** 定时 flush 间隔（秒） */
    private const val FLUSH_TIMEOUT_SEC = 5L

    private var logDir: File? = null
    private var currentLogFile: File? = null
    private var currentDate: String = ""
    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val buffer = ArrayBlockingQueue<String>(1000)
    private var unflushedCount = 0

    private val flushExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "AppLogger-Flush").apply { isDaemon = true }
    }

    @Volatile
    private var initialized = false

    // ===== 初始化 =====

    /**
     * 初始化日志系统（在 Application.onCreate() 中调用）
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return

        try {
            minLevel = if (BuildConfig.DEBUG) Level.DEBUG else Level.INFO
            logcatEnabled = BuildConfig.DEBUG
            val externalFilesDir = context.getExternalFilesDir(null)
            if (externalFilesDir != null) {
                logDir = File(externalFilesDir, "remoPhoto/logs")
                logDir?.mkdirs()
                rotateLogFile()
            }

            // 定时 flush
            flushExecutor.scheduleWithFixedDelay(
                { flushToFile() },
                FLUSH_TIMEOUT_SEC,
                FLUSH_TIMEOUT_SEC,
                TimeUnit.SECONDS
            )

            initialized = true

            // 启动日志
            i(
                "AppLogger",
                "日志系统已初始化: minLevel=$minLevel, logcat=$logcatEnabled, file=$fileLogEnabled"
            )

        } catch (e: Exception) {
            logFallbackError("日志系统初始化失败", e)
        }
    }

    // ===== 公共 API =====

    /** 记录 DEBUG 级别日志 */
    @JvmStatic
    fun d(tag: String, message: String) {
        log(Level.DEBUG, tag, message, null)
    }

    /** 记录 INFO 级别日志 */
    @JvmStatic
    fun i(tag: String, message: String) {
        log(Level.INFO, tag, message, null)
    }

    /** 记录 WARN 级别日志 */
    @JvmStatic
    fun w(tag: String, message: String) {
        log(Level.WARN, tag, message, null)
    }

    /** 记录 ERROR 级别日志 */
    @JvmStatic
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.ERROR, tag, message, throwable)
    }

    /**
     * 强制将缓冲中的日志写入文件
     */
    @JvmStatic
    fun flush() {
        flushToFile()
    }

    /**
     * 获取当前日志文件路径（供调试使用）
     *
     * @return 日志文件绝对路径，若未初始化则返回 null
     */
    fun getCurrentLogPath(): String? = currentLogFile?.absolutePath

    // ===== 内部实现 =====

    private fun log(level: Level, tag: String, message: String, throwable: Throwable?) {
        if (level.priority < minLevel.priority) return

        val timestamp = System.currentTimeMillis()
        val safeMessage = sanitizeForBuild(message)
        val line = buildLogLine(level, tag, safeMessage, throwable, timestamp)

        // 输出到 Logcat
        if (logcatEnabled) {
            when (level) {
                Level.DEBUG -> Log.d(tag, safeMessage, throwable)
                Level.INFO -> Log.i(tag, safeMessage, throwable)
                Level.WARN -> Log.w(tag, safeMessage, throwable)
                Level.ERROR -> Log.e(tag, safeMessage, throwable)
            }
        }

        // 写入缓冲
        if (fileLogEnabled && initialized) {
            buffer.offer(line)
            val count = unflushedCount + 1
            unflushedCount = count
            if (count >= FLUSH_INTERVAL) {
                flushToFile()
            }
        }
    }

    private fun buildLogLine(
        level: Level,
        tag: String,
        message: String,
        throwable: Throwable?,
        timestamp: Long
    ): String {
        val sb = StringBuilder()
        val timeStr = synchronized(timeFormat) { timeFormat.format(Date(timestamp)) }
        sb.append(timeStr)
        sb.append(" ")
        sb.append(level.emoji)
        sb.append(" [")
        sb.append(tag)
        sb.append("] ")
        sb.append(message)
        if (throwable != null) {
            sb.append("\n")
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            sb.append(sanitizeForBuild(sw.toString()))
        }
        return sb.toString()
    }

    @Synchronized
    private fun flushToFile() {
        if (buffer.isEmpty()) return
        try {
            rotateLogFile()
            val file = currentLogFile ?: return
            val writer = FileWriter(file, true)
            try {
                var line = buffer.poll()
                while (line != null) {
                    writer.append(line).append("\n")
                    line = buffer.poll()
                }
                unflushedCount = 0
            } finally {
                writer.close()
            }
        } catch (e: Exception) {
            logFallbackError("日志写入文件失败", e)
        }
    }

    @Synchronized
    private fun rotateLogFile() {
        val dir = logDir ?: return
        val today = synchronized(dateFormat) { dateFormat.format(Date()) }
        if (today != currentDate || currentLogFile == null) {
            currentDate = today
            currentLogFile = File(dir, "app_${today}.log")
        }
    }

    private fun sanitizeForBuild(value: String): String =
        if (BuildConfig.DEBUG) value else ReleaseLogSanitizer.sanitize(value)

    private fun logFallbackError(message: String, throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            Log.e("AppLogger", message, throwable)
        } else {
            // 文件日志不可用时仍保留最小故障信号，但不输出可能含路径的异常详情。
            Log.e("AppLogger", "$message: ${throwable.javaClass.simpleName}")
        }
    }
}
