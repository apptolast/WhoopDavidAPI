package com.example.whoopdavidapi.client

import com.example.whoopdavidapi.exception.WhoopApiException
import com.example.whoopdavidapi.model.entity.OAuthTokenEntity
import com.example.whoopdavidapi.repository.OAuthTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant

@Component
class WhoopTokenManager(
    private val tokenRepository: OAuthTokenRepository,
    @Value("\${spring.security.oauth2.client.registration.whoop.client-id}") private val clientId: String,
    @Value("\${spring.security.oauth2.client.registration.whoop.client-secret}") private val clientSecret: String,
    @Value("\${spring.security.oauth2.client.provider.whoop.token-uri}") private val tokenUri: String
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val refreshClient = RestClient.builder().build()

    fun getValidAccessToken(): String {
        val token = tokenRepository.findTopByOrderByUpdatedAtDesc()
            ?: throw WhoopApiException("No hay token OAuth2 guardado. Realiza el flujo de autorizacion primero.")

        // Si el token expira en menos de 5 minutos, refrescarlo
        if (token.expiresAt.isBefore(Instant.now().plusSeconds(300))) {
            log.info("Access token expira pronto, refrescando...")
            return refreshToken(token)
        }

        return token.accessToken
    }

    fun saveToken(accessToken: String, refreshToken: String?, expiresInSeconds: Long, scope: String?) {
        val entity = tokenRepository.findTopByOrderByUpdatedAtDesc() ?: OAuthTokenEntity()
        entity.accessToken = accessToken
        entity.refreshToken = refreshToken ?: entity.refreshToken
        entity.expiresAt = Instant.now().plusSeconds(expiresInSeconds)
        entity.scope = scope
        entity.updatedAt = Instant.now()
        if (entity.id == null) {
            entity.createdAt = Instant.now()
        }
        tokenRepository.save(entity)
        log.info("Token OAuth2 guardado, expira en {} segundos", expiresInSeconds)
    }

    private fun refreshToken(token: OAuthTokenEntity): String {
        val refreshToken = token.refreshToken
            ?: throw WhoopApiException("No hay refresh token disponible. Realiza el flujo de autorizacion de nuevo.")

        val response = refreshClient.post()
            .uri(tokenUri)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(
                "grant_type=refresh_token" +
                "&refresh_token=$refreshToken" +
                "&client_id=$clientId" +
                "&client_secret=$clientSecret"
            )
            .retrieve()
            .body(Map::class.java)
            ?: throw WhoopApiException("Respuesta vacia al refrescar token")

        val newAccessToken = response["access_token"] as? String
            ?: throw WhoopApiException("No se recibio access_token al refrescar")
        val newRefreshToken = response["refresh_token"] as? String
        val expiresIn = (response["expires_in"] as? Number)?.toLong() ?: 3600L
        val scope = response["scope"] as? String

        saveToken(newAccessToken, newRefreshToken, expiresIn, scope)
        log.info("Token refrescado exitosamente")
        return newAccessToken
    }
}
