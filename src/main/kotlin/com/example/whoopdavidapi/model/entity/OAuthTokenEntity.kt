package com.example.whoopdavidapi.model.entity

import com.example.whoopdavidapi.util.EncryptedStringConverter
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "oauth_tokens")
class OAuthTokenEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "access_token", length = 4096)
    @Convert(converter = EncryptedStringConverter::class)
    // Length 4096: Los tokens cifrados con AES-256-GCM requieren espacio adicional:
    // - IV (12 bytes) + token original (~2KB max) + GCM tag (16 bytes) = ~2.028KB
    // - Base64 encoding agrega 33.33% overhead (ratio 4/3) = ~2.7KB
    // - Margen de seguridad de ~1.3KB para tokens más largos o cambios futuros
    var accessToken: String? = null,

    @Column(name = "refresh_token", length = 4096)
    @Convert(converter = EncryptedStringConverter::class)
    // Length 4096: mismo cálculo que accessToken
    var refreshToken: String? = null,

    @Column(name = "token_type")
    var tokenType: String = "Bearer",

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.now(),

    @Column(name = "scope")
    var scope: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)
