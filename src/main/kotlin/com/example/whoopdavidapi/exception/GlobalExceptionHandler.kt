package com.example.whoopdavidapi.exception

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(WhoopApiException::class)
    fun handleWhoopApiException(ex: WhoopApiException): ResponseEntity<ErrorResponse> {
        log.error("Whoop API error: {}", ex.message, ex)
        val status = when (ex.statusCode) {
            429 -> HttpStatus.TOO_MANY_REQUESTS
            401, 403 -> HttpStatus.BAD_GATEWAY
            else -> HttpStatus.BAD_GATEWAY
        }
        return ResponseEntity.status(status).body(
            ErrorResponse(
                error = status.reasonPhrase,
                message = ex.message ?: "Error comunicandose con Whoop API",
                status = status.value()
            )
        )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        log.warn("Bad request: {}", ex.message)
        return ResponseEntity.badRequest().body(
            ErrorResponse(
                error = "Bad Request",
                message = ex.message ?: "Parametro invalido",
                status = 400
            )
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unexpected error", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorResponse(
                error = "Internal Server Error",
                message = "Error interno del servidor",
                status = 500
            )
        )
    }
}

data class ErrorResponse(
    val error: String,
    val message: String,
    val status: Int
)
