package dev.daybook.app.data
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec

class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("private_credentials", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("daybook_openai_v1", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("daybook_openai_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false).build())
        }.generateKey()
    }
    @Synchronized fun hasKey() = prefs.contains("ciphertext")
    @Synchronized fun save(value: String) {
        if (value.isBlank()) { check(prefs.edit().clear().commit()) { "Could not remove the API key." }; return }
        require(value.trim().length in 20..1000 && !value.any { it.isWhitespace() }) { "Enter a valid API key." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.trim().toByteArray())
        check(prefs.edit().putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()) { "Could not save the API key." }
    }
    @Synchronized fun read(): String {
        val value = prefs.getString("ciphertext", null) ?: return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
            }
            String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)))
        } catch (e: Exception) { throw IllegalStateException("Your saved API key cannot be unlocked. Enter it again in Settings.") }
    }
}
