package com.example.whoopdavidapi.util

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Utilidad para cifrar/descifrar tokens OAuth2 en reposo.
 * Usa AES-128 con una clave configurada via variables de entorno.
 * IMPORTANTE: La clave debe ser de 16 bytes (128 bits) y mantenerse secreta.
 */
@Component
class TokenEncryptor(
    @Value("\${app.security.encryption-key:default-key-1234}") private val encryptionKey: String
) {
    private val algorithm = "AES"
    private val keySpec: SecretKeySpec

    init {
        // Asegurar que la clave tenga exactamente 16 bytes para AES-128
        val keyBytes = encryptionKey.padEnd(16, '0').take(16).toByteArray(Charsets.UTF_8)
        keySpec = SecretKeySpec(keyBytes, algorithm)
    }

    fun encrypt(plainText: String?): String? {
        if (plainText.isNullOrBlank()) return null
        
        return try {
            val cipher = Cipher.getInstance(algorithm)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(encryptedBytes)
        } catch (ex: Exception) {
            throw IllegalStateException("Error cifrando token: ${ex.message}", ex)
        }
    }

    fun decrypt(encryptedText: String?): String? {
        if (encryptedText.isNullOrBlank()) return null
        
        return try {
            val cipher = Cipher.getInstance(algorithm)
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val decodedBytes = Base64.getDecoder().decode(encryptedText)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (ex: Exception) {
            throw IllegalStateException("Error descifrando token: ${ex.message}", ex)
        }
    }
}
