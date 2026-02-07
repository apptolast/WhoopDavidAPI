package com.example.whoopdavidapi.repository

import com.example.whoopdavidapi.model.entity.WhoopWorkout
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface WorkoutRepository : JpaRepository<WhoopWorkout, String> {

    fun findByStartBetween(from: Instant, to: Instant, pageable: Pageable): Page<WhoopWorkout>

    fun findByStartGreaterThanEqual(from: Instant, pageable: Pageable): Page<WhoopWorkout>

    fun findByStartLessThan(to: Instant, pageable: Pageable): Page<WhoopWorkout>

    fun findTopByOrderByUpdatedAtDesc(): WhoopWorkout?
}
