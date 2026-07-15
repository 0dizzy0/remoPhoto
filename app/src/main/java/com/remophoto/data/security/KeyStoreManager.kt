package com.remophoto.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.remophoto.util.AppLogger
import java.io.File
import java.security.KeyStore
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore 凭据管理器
 *
 * 使用 AndroidKeyStore 硬件级安全（TEE/StrongBox）存储远程连接凭据。
 * 凭据不入 Room DB，不会随 DatabaseExporter 导出。
 * 设备间传输时凭据不可恢复（Keystore 绑定硬件），需用户重新输入。
 *
 * 加密方案：
 * - AndroidKeyStore 生成 AES-256/GCM 密钥（硬件保护，不可导出）
 * - 使用该密钥加密凭据明文，IV + 密文拼接存储到 app 内部文件
 * - 安全性由 Keystore 管理的密钥保证（即使内部文件被窃取，无密钥无法解密）
 *
 * Key 别名格式：remote_conn_{connectionId}
 *
 * 用法：
 * ```
 * val keyStoreManager = KeyStoreManager(context)
 * keyStoreManager.storeCredential(connId, passwordChars)
 * val pwd = keyStoreManager.getCredential(connId)
 * keyStoreManager.deleteCredential(connId)
 * ```
 */
class KeyStoreManager(private val context: Context) : CredentialStore {

    companion object {
        private const val TAG = "KeyStoreManager"
    }

    private val keystore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    private val credDir: File by lazy {
        File(context.filesDir, "keystore_credentials").also { it.mkdirs() }
    }

    // ===== Public API =====

    /**
     * 存储凭据
     *
     * @param connectionId 远程连接 ID
     * @param credential 凭据明文（SMB 密码 / HTTP Token）
     */
    override fun storeCredential(connectionId: Long, credential: CharArray) {
        var plainBytes: ByteArray? = null
        try {
            val alias = keyAlias(connectionId)

            // 确保 AES 密钥存在（首次使用时生成）
            if (!keystore.containsAlias(alias)) {
                generateAesKey(alias)
            }

            val secretKey = keystore.getKey(alias, null) as SecretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            val iv = cipher.iv // GCM 随机 IV，12 bytes
            val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(credential))
            plainBytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
            val encrypted = cipher.doFinal(plainBytes)

            // IV（12 bytes）+ 密文拼接存储（IV 不需要保密，但必须是随机的）
            val target = File(credDir, alias)
            val temporary = File(credDir, "$alias.tmp")
            val blob = iv + encrypted
            try {
                temporary.writeBytes(blob)
                try {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } finally {
                blob.fill(0)
                encrypted.fill(0)
                iv.fill(0)
                temporary.delete()
            }

            AppLogger.d(TAG, "凭据已存储: connectionId=$connectionId")
        } catch (e: Exception) {
            AppLogger.e(TAG, "存储凭据失败: connectionId=$connectionId, category=${e.javaClass.simpleName}")
            throw SecurityException("无法存储凭据", e)
        } finally {
            plainBytes?.fill(0)
        }
    }

    /**
     * 读取凭据
     *
     * @return 凭据明文，如果不存在则返回 null
     */
    override fun getCredential(connectionId: Long): CharArray? {
        return try {
            val alias = keyAlias(connectionId)

            if (!keystore.containsAlias(alias)) return null

            val blobFile = File(credDir, alias)
            if (!blobFile.exists()) return null

            val combined = blobFile.readBytes()
            if (combined.size <= 12) {
                combined.fill(0)
                return null
            }
            val iv = combined.copyOfRange(0, 12)
            val encrypted = combined.copyOfRange(12, combined.size)

            val secretKey = keystore.getKey(alias, null) as SecretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val decrypted = cipher.doFinal(encrypted)
            try {
                val decoded = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(decrypted))
                CharArray(decoded.remaining()).also { decoded.get(it) }
            } finally {
                decrypted.fill(0)
                combined.fill(0)
                iv.fill(0)
                encrypted.fill(0)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "读取凭据失败: connectionId=$connectionId, category=${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * 删除凭据（同时清除 Keystore 密钥和加密 blob）
     */
    override fun deleteCredential(connectionId: Long) {
        try {
            val alias = keyAlias(connectionId)
            if (keystore.containsAlias(alias)) {
                keystore.deleteEntry(alias)
            }
            val blob = File(credDir, alias)
            check(!blob.exists() || blob.delete()) { "无法删除凭据文件" }
            AppLogger.d(TAG, "凭据已删除: connectionId=$connectionId")
        } catch (e: Exception) {
            AppLogger.e(TAG, "删除凭据失败: connectionId=$connectionId, category=${e.javaClass.simpleName}")
            throw SecurityException("无法删除凭据", e)
        }
    }

    /**
     * 检查凭据是否存在
     */
    override fun hasCredential(connectionId: Long): Boolean {
        return try {
            val alias = keyAlias(connectionId)
            keystore.containsAlias(alias) && File(credDir, alias).exists()
        } catch (e: Exception) {
            false
        }
    }

    // ===== Private =====

    private fun keyAlias(connectionId: Long): String = "remote_conn_$connectionId"

    /**
     * 生成 AES-256/GCM 密钥（Android Keystore 硬件保护，不可导出）
     */
    private fun generateAesKey(alias: String) {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        keyGenerator.generateKey()
        AppLogger.d(TAG, "AES-256/GCM 密钥已生成: alias=$alias")
    }
}
