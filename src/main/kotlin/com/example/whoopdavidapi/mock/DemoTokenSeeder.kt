package com.example.whoopdavidapi.mock

import com.example.whoopdavidapi.model.entity.OAuthTokenEntity
import com.example.whoopdavidapi.repository.OAuthTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@Profile("demo")
class DemoTokenSeeder(
    private val tokenRepository: OAuthTokenRepository
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(vararg args: String) {
        if (tokenRepository.count() == 0L) {
            val token = OAuthTokenEntity(
                accessToken = "demo-access-token",
                refreshToken = "demo-refresh-token",
                tokenType = "Bearer",
                expiresAt = Instant.now().plusSeconds(86400),
                scope = "offline,read:profile,read:cycles,read:recovery,read:sleep,read:workout",
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
            tokenRepository.save(token)
            log.info("Token OAuth2 demo insertado en BD")
        }
    }
}
