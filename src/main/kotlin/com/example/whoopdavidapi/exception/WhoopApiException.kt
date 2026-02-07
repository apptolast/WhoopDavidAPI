package com.example.whoopdavidapi.exception

class WhoopApiException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)
