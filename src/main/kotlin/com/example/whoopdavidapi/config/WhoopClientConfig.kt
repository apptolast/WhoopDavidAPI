package com.example.whoopdavidapi.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.ClientHttpRequestFactories
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class WhoopClientConfig(
    @Value("\${app.whoop.base-url}") private val baseUrl: String
) {

    @Bean
    fun whoopRestClient(): RestClient {
        // Configurar timeouts para evitar bloqueos indefinidos
        val settings = ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(Duration.ofSeconds(10))
            .withReadTimeout(Duration.ofSeconds(30))
        
        val requestFactory: ClientHttpRequestFactory = 
            ClientHttpRequestFactories.get(settings)

        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(requestFactory)
            .build()
    }
}
