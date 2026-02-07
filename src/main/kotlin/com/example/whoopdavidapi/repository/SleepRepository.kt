package com.example.whoopdavidapi.repository

import com.example.whoopdavidapi.model.entity.WhoopSleep
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface SleepRepository : JpaRepository<WhoopSleep, String> {

    fun findByStartBetween(from: Instant, to: Instant, pageable: Pageable): Page<WhoopSleep>

    fun findByStartGreaterThanEqual(from: Instant, pageable: Pageable): Page<WhoopSleep>

    fun findByStartLessThan(to: Instant, pageable: Pageable): Page<WhoopSleep>

    fun findTopByOrderByUpdatedAtDesc(): WhoopSleep?
}
