package com.example.whoopdavidapi.repository

import com.example.whoopdavidapi.model.entity.WhoopRecovery
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface RecoveryRepository : JpaRepository<WhoopRecovery, Long> {

    fun findByCreatedAtBetween(from: Instant, to: Instant, pageable: Pageable): Page<WhoopRecovery>

    fun findByCreatedAtGreaterThanEqual(from: Instant, pageable: Pageable): Page<WhoopRecovery>

    fun findByCreatedAtLessThan(to: Instant, pageable: Pageable): Page<WhoopRecovery>

    fun findTopByOrderByUpdatedAtDesc(): WhoopRecovery?
}
