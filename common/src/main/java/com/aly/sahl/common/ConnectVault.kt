package com.aly.sahl.common

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials never enter Android backup; phone bearer keys are encrypted with a device-local key. */
class ConnectVault(ctx: Context, name: String) {
    private val file = AtomicFile(File(ctx.noBackupFilesDir, "connect-$name"))
    private val alias = "sahl-connect-vault-v1"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(): String? {
        if (!file.baseFile.exists()) return null
        val b = file.readFully()
        require(b.size >= 29)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
            doFinal(b.copyOfRange(12, b.size)).toString(Charsets.UTF_8)
        }
    }
    @Synchronized fun write(value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val b = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
        val out = file.startWrite()
        try { out.write(b); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }
    @Synchronized fun clear() { file.delete() }
}
