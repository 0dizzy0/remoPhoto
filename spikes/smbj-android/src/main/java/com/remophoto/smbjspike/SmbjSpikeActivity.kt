package com.remophoto.smbjspike

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.util.concurrent.Executors

/**
 * M0 专用、可整体删除的 SMBJ Android 真机探针。
 *
 * 凭据只从密码输入框进入内存，不通过 Intent、Room、文件或日志传递。
 */
class SmbjSpikeActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val host = field("主机名或 IP")
        val share = field("共享名")
        val username = field("用户名")
        val domain = field("域（可选）")
        val password = field("密码").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val result = TextView(this).apply { text = "尚未执行" }
        val run = Button(this).apply { text = "运行 SMBJ Spike" }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(host)
            addView(share)
            addView(username)
            addView(domain)
            addView(password)
            addView(run)
            addView(result)
        }
        setContentView(ScrollView(this).apply { addView(content) })

        run.setOnClickListener {
            val passwordChars = password.text.toString().toCharArray()
            val input = SmbjProbeInput(
                host = host.text.toString().trim(),
                share = share.text.toString().trim(),
                username = username.text.toString(),
                domain = domain.text.toString(),
                password = passwordChars,
            )
            if (!input.isValid()) {
                passwordChars.fill('\u0000')
                result.text = "FAIL: 主机、共享名、用户名和密码均为必填项"
                return@setOnClickListener
            }

            run.isEnabled = false
            result.text = "RUNNING"
            executor.execute {
                val report = SmbjCompatibilityProbe.run(input)
                runOnUiThread {
                    result.text = report.displayText
                    run.isEnabled = true
                    password.text.clear()
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
}

internal data class SmbjProbeInput(
    val host: String,
    val share: String,
    val username: String,
    val domain: String,
    val password: CharArray,
) {
    fun isValid(): Boolean =
        host.isNotBlank() && share.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
}

internal data class SmbjProbeReport(
    val success: Boolean,
    val entryCount: Int = 0,
    val elapsedMs: Long,
    val errorCategory: String? = null,
) {
    val displayText: String
        get() = if (success) {
            "PASS: entries=$entryCount, elapsedMs=$elapsedMs"
        } else {
            "FAIL: category=$errorCategory, elapsedMs=$elapsedMs"
        }
}

internal object SmbjCompatibilityProbe {
    private const val TAG = "SmbjAndroidSpike"

    fun run(input: SmbjProbeInput): SmbjProbeReport {
        val startedAt = System.currentTimeMillis()
        Log.i(TAG, "[spike] stage=start")
        return try {
            SMBClient().use { client ->
                client.connect(input.host).use { connection ->
                    Log.i(TAG, "[spike] stage=transport-connected")
                    val authentication = AuthenticationContext(
                        input.username,
                        input.password,
                        input.domain.ifBlank { null },
                    )
                    connection.authenticate(authentication).use { session ->
                        Log.i(TAG, "[spike] stage=authenticated")
                        (session.connectShare(input.share) as DiskShare).use { diskShare ->
                            val entries = diskShare.list("")
                            val elapsed = System.currentTimeMillis() - startedAt
                            Log.i(
                                TAG,
                                "[spike] stage=complete entries=${entries.size} elapsedMs=$elapsed",
                            )
                            SmbjProbeReport(
                                success = true,
                                entryCount = entries.size,
                                elapsedMs = elapsed,
                            )
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            val elapsed = System.currentTimeMillis() - startedAt
            val category = error.javaClass.simpleName.ifBlank { "Unknown" }
            Log.e(TAG, "[spike] stage=failed category=$category elapsedMs=$elapsed")
            SmbjProbeReport(
                success = false,
                elapsedMs = elapsed,
                errorCategory = category,
            )
        } finally {
            input.password.fill('\u0000')
            Log.i(TAG, "[spike] stage=closed")
        }
    }
}
