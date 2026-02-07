package com.example.whoopdavidapi.client

import com.example.whoopdavidapi.exception.WhoopApiException
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.ratelimiter.annotation.RateLimiter
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant

@Component
class WhoopApiClient(
    private val whoopRestClient: RestClient,
    private val tokenManager: WhoopTokenManager
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // Respuesta generica de coleccion paginada de Whoop
    data class WhoopPageResponse(
        val records: List<Map<String, Any?>> = emptyList(),
        val next_token: String? = null
    )

    @CircuitBreaker(name = "whoopApi", fallbackMethod = "fallbackGetAllRecords")
    @Retry(name = "whoopApi")
    @RateLimiter(name = "whoopApi")
    fun getAllCycles(start: Instant? = null, end: Instant? = null): List<Map<String, Any?>> {
        return getAllRecords("/developer/v1/cycle", start, end)
    }

    @CircuitBreaker(name = "whoopApi", fallbackMethod = "fallbackGetAllRecords")
    @Retry(name = "whoopApi")
    @RateLimiter(name = "whoopApi")
    fun getAllRecoveries(start: Instant? = null, end: Instant? = null): List<Map<String, Any?>> {
        return getAllRecords("/developer/v1/recovery", start, end)
    }

    @CircuitBreaker(name = "whoopApi", fallbackMethod = "fallbackGetAllRecords")
    @Retry(name = "whoopApi")
    @RateLimiter(name = "whoopApi")
    fun getAllSleeps(start: Instant? = null, end: Instant? = null): List<Map<String, Any?>> {
        return getAllRecords("/developer/v1/activity/sleep", start, end)
    }

    @CircuitBreaker(name = "whoopApi", fallbackMethod = "fallbackGetAllRecords")
    @Retry(name = "whoopApi")
    @RateLimiter(name = "whoopApi")
    fun getAllWorkouts(start: Instant? = null, end: Instant? = null): List<Map<String, Any?>> {
        return getAllRecords("/developer/v1/activity/workout", start, end)
    }

    @CircuitBreaker(name = "whoopApi", fallbackMethod = "fallbackGetProfile")
    @Retry(name = "whoopApi")
    @RateLimiter(name = "whoopApi")
    fun getUserProfile(): Map<String, Any?> {
        val token = tokenManager.getValidAccessToken()
        return whoopRestClient.get()
            .uri("/developer/v1/user/profile/basic")
            .header("Authorization", "Bearer $token")
            .retrieve()
            .body(Map::class.java) as? Map<String, Any?>
            ?: throw WhoopApiException("Respuesta vacia al obtener perfil")
    }

    private fun getAllRecords(path: String, start: Instant?, end: Instant?): List<Map<String, Any?>> {
        val allRecords = mutableListOf<Map<String, Any?>>()
        var nextToken: String? = null

        do {
            val token = tokenManager.getValidAccessToken()
            val response = whoopRestClient.get()
                .uri { uriBuilder ->
                    uriBuilder.path(path)
                        .queryParam("limit", 25)
                    start?.let { uriBuilder.queryParam("start", it.toString()) }
                    end?.let { uriBuilder.queryParam("end", it.toString()) }
                    nextToken?.let { uriBuilder.queryParam("nextToken", it) }
                    uriBuilder.build()
                }
                .header("Authorization", "Bearer $token")
                .retrieve()
                .body(WhoopPageResponse::class.java)
                ?: break

            allRecords.addAll(response.records)
            nextToken = response.next_token
            log.debug("Obtenidos {} registros de {}, nextToken={}", response.records.size, path, nextToken)
        } while (nextToken != null)

        log.info("Total {} registros obtenidos de {}", allRecords.size, path)
        return allRecords
    }

    // Fallbacks para circuit breaker
    @Suppress("UNUSED_PARAMETER")
    private fun fallbackGetAllRecords(start: Instant?, end: Instant?, ex: Throwable): List<Map<String, Any?>> {
        log.warn("Circuit breaker abierto para Whoop API: {}", ex.message)
        return emptyList()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun fallbackGetProfile(ex: Throwable): Map<String, Any?> {
        log.warn("Circuit breaker abierto para perfil Whoop: {}", ex.message)
        return mapOf("error" to "Whoop API no disponible temporalmente")
    }
}
