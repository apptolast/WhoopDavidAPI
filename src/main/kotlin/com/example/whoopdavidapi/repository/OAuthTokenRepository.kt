package com.example.whoopdavidapi.repository

import com.example.whoopdavidapi.model.entity.OAuthTokenEntity
import org.springframework.data.jpa.repository.JpaRepository

interface OAuthTokenRepository : JpaRepository<OAuthTokenEntity, Long> {

    fun findTopByOrderByUpdatedAtDesc(): OAuthTokenEntity?
}
