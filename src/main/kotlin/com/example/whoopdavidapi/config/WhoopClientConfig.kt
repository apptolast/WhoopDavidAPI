package com.example.whoopdavidapi.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

@Configuration
class WhoopClientConfig(
    @Value("\${app.whoop.base-url}") private val baseUrl: String,
    @Value("\${app.whoop.connect-timeout:10}") private val connectTimeoutSeconds: Long,
    @Value("\${app.whoop.read-timeout:30}") private val readTimeoutSeconds: Long
) {

    @Bean
    fun whoopRestClient(): RestClient {
        // Configurar timeouts para evitar bloqueos indefinidos
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(java.time.Duration.ofSeconds(connectTimeoutSeconds))
            setReadTimeout(java.time.Duration.ofSeconds(readTimeoutSeconds))
        }

        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(requestFactory)
            .build()
    }
}
