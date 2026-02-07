package com.example.whoopdavidapi.scheduler

import com.example.whoopdavidapi.service.WhoopSyncService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class WhoopDataSyncScheduler(
    private val whoopSyncService: WhoopSyncService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${app.whoop.sync-cron}")
    fun scheduledSync() {
        log.info("Ejecutando sincronizacion programada...")
        try {
            whoopSyncService.syncAll()
        } catch (ex: Exception) {
            log.error("Error en sincronizacion programada: {}", ex.message, ex)
        }
    }
}
