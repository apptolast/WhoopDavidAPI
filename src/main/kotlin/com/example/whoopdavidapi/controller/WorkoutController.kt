package com.example.whoopdavidapi.controller

import com.example.whoopdavidapi.model.dto.PaginatedResponse
import com.example.whoopdavidapi.model.dto.WorkoutDTO
import com.example.whoopdavidapi.service.WorkoutService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1")
class WorkoutController(private val workoutService: WorkoutService) {

    @GetMapping("/workouts")
    fun getWorkouts(
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "100") pageSize: Int
    ): ResponseEntity<PaginatedResponse<WorkoutDTO>> {
        require(page >= 1) { "page debe ser >= 1" }
        require(pageSize in 1..1000) { "pageSize debe estar entre 1 y 1000" }
        return ResponseEntity.ok(workoutService.getWorkouts(from, to, page, pageSize))
    }
}
