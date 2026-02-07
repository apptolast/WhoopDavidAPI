package com.example.whoopdavidapi.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class WhoopClientConfig(
    @Value("\${app.whoop.base-url}") private val baseUrl: String
) {

    @Bean
    fun whoopRestClient(): RestClient {
        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .build()
    }
}
