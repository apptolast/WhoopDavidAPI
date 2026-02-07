package com.example.whoopdavidapi.util

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Utilidad para cifrar/descifrar tokens OAuth2 en reposo.
 * Usa AES-256-GCM (AEAD) con IV aleatorio para cada operación de cifrado.
 * IMPORTANTE: La clave debe ser Base64 de exactamente 32 bytes (256 bits).
 */
@Component
class TokenEncryptor(
    @Value("\${app.security.encryption-key:#{null}}") private val encryptionKey: String?
) {
    private val algorithm = "AES/GCM/NoPadding"
    private val keySpec: SecretKeySpec
    private val secureRandom = SecureRandom()
    
    companion object {
        private const val GCM_IV_LENGTH = 12 // 96 bits recommended for GCM
        private const val GCM_TAG_LENGTH = 128 // 128 bits authentication tag
    }

    init {
        // Fallar si no hay clave configurada
        require(!encryptionKey.isNullOrBlank()) {
            "app.security.encryption-key debe estar configurada. No se permite clave por defecto."
        }
        
        // Interpretar la clave como Base64 y decodificarla
        val keyBytes = try {
            Base64.getDecoder().decode(encryptionKey)
        } catch (ex: IllegalArgumentException) {
            throw IllegalArgumentException(
                "app.security.encryption-key debe ser una cadena Base64 válida que represente exactamente 32 bytes. " +
                "Ejemplo para generar: openssl rand -base64 32",
                ex
            )
        }

        // Validar que la clave decodificada tenga exactamente 32 bytes (256 bits)
        require(keyBytes.size == 32) {
            "app.security.encryption-key debe representar exactamente 32 bytes (256 bits) tras decodificar Base64. " +
            "Actual: ${keyBytes.size} bytes. Genera una clave segura con: openssl rand -base64 32"
        }

        // Usar directamente los 32 bytes decodificados como clave AES-256
        keySpec = SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(plainText: String?): String? {
        // Solo retornar null si el valor es null (no si es blank)
        if (plainText == null) return null
        
        return try {
            val cipher = Cipher.getInstance(algorithm)
            
            // Generar IV aleatorio de 12 bytes (96 bits) para GCM
            val iv = ByteArray(GCM_IV_LENGTH)
            secureRandom.nextBytes(iv)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            
            // Combinar IV + ciphertext (que incluye el tag de autenticación) y codificar en Base64
            val combined = iv + encryptedBytes
            Base64.getEncoder().encodeToString(combined)
        } catch (ex: Exception) {
            throw IllegalStateException("Error procesando credenciales")
        }
    }

    fun decrypt(encryptedText: String?): String? {
        if (encryptedText == null) return null
        
        return try {
            val cipher = Cipher.getInstance(algorithm)
            
            // Decodificar Base64 y separar IV + ciphertext
            val combined = Base64.getDecoder().decode(encryptedText)
            
            // Validar que existan al menos 12 bytes de IV y > 0 bytes de ciphertext
            if (combined.size <= GCM_IV_LENGTH) {
                throw IllegalStateException("Datos cifrados inválidos")
            }
            
            val iv = combined.take(GCM_IV_LENGTH).toByteArray()
            val ciphertext = combined.drop(GCM_IV_LENGTH).toByteArray()
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (ex: Exception) {
            throw IllegalStateException("Error procesando credenciales")
        }
    }
}
