package com.example.whoopdavidapi.util

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Utilidad para cifrar/descifrar tokens OAuth2 en reposo.
 * Usa AES-256-CBC con IV aleatorio para cada operación de cifrado.
 * IMPORTANTE: La clave debe ser de 32 bytes (256 bits) y configurarse via variable de entorno.
 */
@Component
class TokenEncryptor(
    @Value("\${app.security.encryption-key:#{null}}") private val encryptionKey: String?
) {
    private val algorithm = "AES/CBC/PKCS5Padding"
    private val keySpec: SecretKeySpec
    private val secureRandom = SecureRandom()

    init {
        // Fallar si no hay clave configurada
        require(!encryptionKey.isNullOrBlank()) {
            "app.security.encryption-key debe estar configurada. No se permite clave por defecto."
        }
        
        // Validar que la clave tenga al menos 32 bytes
        val keyBytes = encryptionKey.toByteArray(Charsets.UTF_8)
        require(keyBytes.size >= 32) {
            "app.security.encryption-key debe tener al menos 32 bytes (caracteres). Actual: ${keyBytes.size} bytes. " +
            "Genera una clave segura con: openssl rand -base64 32"
        }
        
        // Usar exactamente los primeros 32 bytes
        keySpec = SecretKeySpec(keyBytes.copyOf(32), "AES")
    }

    fun encrypt(plainText: String?): String? {
        if (plainText.isNullOrBlank()) return null
        
        return try {
            val cipher = Cipher.getInstance(algorithm)
            
            // Generar IV aleatorio de 16 bytes
            val iv = ByteArray(16)
            secureRandom.nextBytes(iv)
            val ivSpec = IvParameterSpec(iv)
            
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            
            // Combinar IV + ciphertext y codificar en Base64
            val combined = iv + encryptedBytes
            Base64.getEncoder().encodeToString(combined)
        } catch (ex: Exception) {
            throw IllegalStateException("Error procesando credenciales: ${ex.message}", ex)
        }
    }

    fun decrypt(encryptedText: String?): String? {
        if (encryptedText.isNullOrBlank()) return null
        
        return try {
            val cipher = Cipher.getInstance(algorithm)
            
            // Decodificar Base64 y separar IV + ciphertext
            val combined = Base64.getDecoder().decode(encryptedText)
            val iv = combined.take(16).toByteArray()
            val ciphertext = combined.drop(16).toByteArray()
            val ivSpec = IvParameterSpec(iv)
            
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (ex: Exception) {
            throw IllegalStateException("Error procesando credenciales: ${ex.message}", ex)
        }
    }
}
