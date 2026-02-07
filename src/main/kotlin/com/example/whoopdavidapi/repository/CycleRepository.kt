package com.example.whoopdavidapi.repository

import com.example.whoopdavidapi.model.entity.WhoopCycle
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface CycleRepository : JpaRepository<WhoopCycle, Long> {

    fun findByStartBetween(from: Instant, to: Instant, pageable: Pageable): Page<WhoopCycle>

    fun findByStartGreaterThanEqual(from: Instant, pageable: Pageable): Page<WhoopCycle>

    fun findByStartLessThan(to: Instant, pageable: Pageable): Page<WhoopCycle>

    fun findTopByOrderByUpdatedAtDesc(): WhoopCycle?
}
