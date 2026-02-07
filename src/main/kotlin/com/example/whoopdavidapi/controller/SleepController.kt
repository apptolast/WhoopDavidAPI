package com.example.whoopdavidapi.controller

import com.example.whoopdavidapi.model.dto.PaginatedResponse
import com.example.whoopdavidapi.model.dto.SleepDTO
import com.example.whoopdavidapi.service.SleepService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1")
class SleepController(private val sleepService: SleepService) {

    @GetMapping("/sleep")
    fun getSleeps(
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "100") pageSize: Int
    ): ResponseEntity<PaginatedResponse<SleepDTO>> {
        require(page >= 1) { "page debe ser >= 1" }
        require(pageSize in 1..1000) { "pageSize debe estar entre 1 y 1000" }
        return ResponseEntity.ok(sleepService.getSleeps(from, to, page, pageSize))
    }
}
