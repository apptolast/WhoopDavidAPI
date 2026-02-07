package com.example.whoopdavidapi.controller

import com.example.whoopdavidapi.model.dto.PaginatedResponse
import com.example.whoopdavidapi.model.dto.RecoveryDTO
import com.example.whoopdavidapi.service.RecoveryService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1")
class RecoveryController(private val recoveryService: RecoveryService) {

    @GetMapping("/recovery")
    fun getRecoveries(
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "100") pageSize: Int
    ): ResponseEntity<PaginatedResponse<RecoveryDTO>> {
        require(page >= 1) { "page debe ser >= 1" }
        require(pageSize in 1..1000) { "pageSize debe estar entre 1 y 1000" }
        return ResponseEntity.ok(recoveryService.getRecoveries(from, to, page, pageSize))
    }
}
