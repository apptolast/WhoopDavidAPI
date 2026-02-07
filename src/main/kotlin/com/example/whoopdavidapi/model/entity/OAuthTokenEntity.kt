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

    @Column(name = "access_token", nullable = false, length = 4096)
    @Convert(converter = EncryptedStringConverter::class)
    var accessToken: String = "",

    @Column(name = "refresh_token", length = 4096)
    @Convert(converter = EncryptedStringConverter::class)
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
