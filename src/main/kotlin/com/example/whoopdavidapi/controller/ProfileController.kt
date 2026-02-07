package com.example.whoopdavidapi.controller

import com.example.whoopdavidapi.client.WhoopApiClient
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class ProfileController(private val whoopApiClient: WhoopApiClient) {

    @GetMapping("/profile")
    fun getProfile(): ResponseEntity<Map<String, Any?>> {
        val profile = whoopApiClient.getUserProfile()
        return ResponseEntity.ok(profile)
    }
}
