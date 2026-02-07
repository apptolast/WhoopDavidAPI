package com.example.whoopdavidapi.util

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import org.springframework.stereotype.Component

/**
 * Conversor JPA para cifrar/descifrar automáticamente tokens sensibles.
 * Se aplica a los campos accessToken y refreshToken de OAuthTokenEntity.
 */
@Converter
@Component
class EncryptedStringConverter(
    private val encryptor: TokenEncryptor
) : AttributeConverter<String?, String?> {

    override fun convertToDatabaseColumn(attribute: String?): String? {
        return encryptor.encrypt(attribute)
    }

    override fun convertToEntityAttribute(dbData: String?): String? {
        return encryptor.decrypt(dbData)
    }
}
