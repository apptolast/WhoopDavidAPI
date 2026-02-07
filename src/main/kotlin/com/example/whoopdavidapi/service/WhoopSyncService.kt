package com.example.whoopdavidapi.service

import com.example.whoopdavidapi.client.WhoopApiClient
import com.example.whoopdavidapi.model.entity.*
import com.example.whoopdavidapi.repository.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class WhoopSyncService(
    private val whoopApiClient: WhoopApiClient,
    private val cycleRepository: CycleRepository,
    private val recoveryRepository: RecoveryRepository,
    private val sleepRepository: SleepRepository,
    private val workoutRepository: WorkoutRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun syncAll() {
        log.info("Iniciando sincronizacion completa con Whoop API...")
        val start = System.currentTimeMillis()

        syncCycles()
        syncRecoveries()
        syncSleeps()
        syncWorkouts()

        val elapsed = System.currentTimeMillis() - start
        log.info("Sincronizacion completa en {} ms", elapsed)
    }

    private fun syncCycles() {
        try {
            // Sincronizacion incremental: obtener solo datos nuevos
            val lastUpdated = cycleRepository.findTopByOrderByUpdatedAtDesc()?.updatedAt
            val records = whoopApiClient.getAllCycles(start = lastUpdated)
            var saved = 0
            var skipped = 0

            for (record in records) {
                try {
                    val cycle = mapToCycle(record)
                    cycleRepository.save(cycle)
                    saved++
                } catch (ex: IllegalArgumentException) {
                    log.warn("Saltando cycle con datos invalidos: {}", ex.message)
                    skipped++
                }
            }

            log.info("Cycles sincronizados: {} nuevos/actualizados, {} saltados", saved, skipped)
        } catch (ex: Exception) {
            log.error("Error sincronizando cycles: {}", ex.message, ex)
        }
    }

    private fun syncRecoveries() {
        try {
            val lastUpdated = recoveryRepository.findTopByOrderByUpdatedAtDesc()?.updatedAt
            val records = whoopApiClient.getAllRecoveries(start = lastUpdated)
            var saved = 0

            for (record in records) {
                val recovery = mapToRecovery(record)
                recoveryRepository.save(recovery)
                saved++
            }

            log.info("Recoveries sincronizados: {} nuevos/actualizados", saved)
        } catch (ex: Exception) {
            log.error("Error sincronizando recoveries: {}", ex.message, ex)
        }
    }

    private fun syncSleeps() {
        try {
            val lastUpdated = sleepRepository.findTopByOrderByUpdatedAtDesc()?.updatedAt
            val records = whoopApiClient.getAllSleeps(start = lastUpdated)
            var saved = 0
            var skipped = 0

            for (record in records) {
                try {
                    val sleep = mapToSleep(record)
                    sleepRepository.save(sleep)
                    saved++
                } catch (ex: IllegalArgumentException) {
                    log.warn("Saltando sleep con datos invalidos: {}", ex.message)
                    skipped++
                }
            }

            log.info("Sleeps sincronizados: {} nuevos/actualizados, {} saltados", saved, skipped)
        } catch (ex: Exception) {
            log.error("Error sincronizando sleeps: {}", ex.message, ex)
        }
    }

    private fun syncWorkouts() {
        try {
            val lastUpdated = workoutRepository.findTopByOrderByUpdatedAtDesc()?.updatedAt
            val records = whoopApiClient.getAllWorkouts(start = lastUpdated)
            var saved = 0
            var skipped = 0

            for (record in records) {
                try {
                    val workout = mapToWorkout(record)
                    workoutRepository.save(workout)
                    saved++
                } catch (ex: IllegalArgumentException) {
                    log.warn("Saltando workout con datos invalidos: {}", ex.message)
                    skipped++
                }
            }

            log.info("Workouts sincronizados: {} nuevos/actualizados, {} saltados", saved, skipped)
        } catch (ex: Exception) {
            log.error("Error sincronizando workouts: {}", ex.message, ex)
        }
    }

    // Mapeo de respuestas JSON de Whoop API a entidades JPA
    private fun mapToCycle(record: Map<String, Any?>): WhoopCycle {
        val score = record["score"] as? Map<*, *>
        return WhoopCycle(
            id = (record["id"] as Number).toLong(),
            userId = (record["user_id"] as Number).toLong(),
            createdAt = parseInstant(record["created_at"]),
            updatedAt = parseInstant(record["updated_at"]),
            start = requireNotNull(parseInstant(record["start"])) { 
                "Campo 'start' requerido en cycle (id=${record["id"] ?: "desconocido"})" 
            },
            end = parseInstant(record["end"]),
            timezoneOffset = record["timezone_offset"] as? String,
            scoreState = record["score_state"] as? String ?: "PENDING_SCORE",
            strain = (score?.get("strain") as? Number)?.toFloat(),
            kilojoule = (score?.get("kilojoule") as? Number)?.toFloat(),
            averageHeartRate = (score?.get("average_heart_rate") as? Number)?.toInt(),
            maxHeartRate = (score?.get("max_heart_rate") as? Number)?.toInt()
        )
    }

    private fun mapToRecovery(record: Map<String, Any?>): WhoopRecovery {
        val score = record["score"] as? Map<*, *>
        return WhoopRecovery(
            cycleId = (record["cycle_id"] as Number).toLong(),
            sleepId = record["sleep_id"] as? String,
            userId = (record["user_id"] as Number).toLong(),
            createdAt = parseInstant(record["created_at"]),
            updatedAt = parseInstant(record["updated_at"]),
            scoreState = record["score_state"] as? String ?: "PENDING_SCORE",
            userCalibrating = score?.get("user_calibrating") as? Boolean,
            recoveryScore = (score?.get("recovery_score") as? Number)?.toFloat(),
            restingHeartRate = (score?.get("resting_heart_rate") as? Number)?.toFloat(),
            hrvRmssdMilli = (score?.get("hrv_rmssd_milli") as? Number)?.toFloat(),
            spo2Percentage = (score?.get("spo2_percentage") as? Number)?.toFloat(),
            skinTempCelsius = (score?.get("skin_temp_celsius") as? Number)?.toFloat()
        )
    }

    private fun mapToSleep(record: Map<String, Any?>): WhoopSleep {
        val score = record["score"] as? Map<*, *>
        val stageSummary = score?.get("stage_summary") as? Map<*, *>
        val sleepNeeded = score?.get("sleep_needed") as? Map<*, *>

        return WhoopSleep(
            id = requireNotNull(record["id"] as? String) { 
                "Campo 'id' requerido en sleep" 
            },
            cycleId = (record["cycle_id"] as? Number)?.toLong(),
            userId = (record["user_id"] as Number).toLong(),
            createdAt = parseInstant(record["created_at"]),
            updatedAt = parseInstant(record["updated_at"]),
            start = requireNotNull(parseInstant(record["start"])) { 
                "Campo 'start' requerido en sleep (id=${record["id"] ?: "desconocido"})" 
            },
            end = parseInstant(record["end"]),
            timezoneOffset = record["timezone_offset"] as? String,
            nap = record["nap"] as? Boolean ?: false,
            scoreState = record["score_state"] as? String ?: "PENDING_SCORE",
            totalInBedTimeMilli = (stageSummary?.get("total_in_bed_time_milli") as? Number)?.toInt(),
            totalAwakeTimeMilli = (stageSummary?.get("total_awake_time_milli") as? Number)?.toInt(),
            totalNoDataTimeMilli = (stageSummary?.get("total_no_data_time_milli") as? Number)?.toInt(),
            totalLightSleepTimeMilli = (stageSummary?.get("total_light_sleep_time_milli") as? Number)?.toInt(),
            totalSlowWaveSleepTimeMilli = (stageSummary?.get("total_slow_wave_sleep_time_milli") as? Number)?.toInt(),
            totalRemSleepTimeMilli = (stageSummary?.get("total_rem_sleep_time_milli") as? Number)?.toInt(),
            sleepCycleCount = (stageSummary?.get("sleep_cycle_count") as? Number)?.toInt(),
            disturbanceCount = (stageSummary?.get("disturbance_count") as? Number)?.toInt(),
            baselineMilli = (sleepNeeded?.get("baseline_milli") as? Number)?.toLong(),
            needFromSleepDebtMilli = (sleepNeeded?.get("need_from_sleep_debt_milli") as? Number)?.toLong(),
            needFromRecentStrainMilli = (sleepNeeded?.get("need_from_recent_strain_milli") as? Number)?.toLong(),
            needFromRecentNapMilli = (sleepNeeded?.get("need_from_recent_nap_milli") as? Number)?.toLong(),
            respiratoryRate = (score?.get("respiratory_rate") as? Number)?.toFloat(),
            sleepPerformancePercentage = (score?.get("sleep_performance_percentage") as? Number)?.toFloat(),
            sleepConsistencyPercentage = (score?.get("sleep_consistency_percentage") as? Number)?.toFloat(),
            sleepEfficiencyPercentage = (score?.get("sleep_efficiency_percentage") as? Number)?.toFloat()
        )
    }

    private fun mapToWorkout(record: Map<String, Any?>): WhoopWorkout {
        val score = record["score"] as? Map<*, *>
        val zones = score?.get("zone_duration") as? Map<*, *>

        return WhoopWorkout(
            id = requireNotNull(record["id"] as? String) { 
                "Campo 'id' requerido en workout" 
            },
            userId = (record["user_id"] as Number).toLong(),
            createdAt = parseInstant(record["created_at"]),
            updatedAt = parseInstant(record["updated_at"]),
            start = requireNotNull(parseInstant(record["start"])) { 
                "Campo 'start' requerido en workout (id=${record["id"] ?: "desconocido"})" 
            },
            end = parseInstant(record["end"]),
            timezoneOffset = record["timezone_offset"] as? String,
            sportName = record["sport_name"] as? String,
            sportId = (record["sport_id"] as? Number)?.toInt(),
            scoreState = record["score_state"] as? String ?: "PENDING_SCORE",
            strain = (score?.get("strain") as? Number)?.toFloat(),
            averageHeartRate = (score?.get("average_heart_rate") as? Number)?.toInt(),
            maxHeartRate = (score?.get("max_heart_rate") as? Number)?.toInt(),
            kilojoule = (score?.get("kilojoule") as? Number)?.toFloat(),
            percentRecorded = (score?.get("percent_recorded") as? Number)?.toFloat(),
            distanceMeter = (score?.get("distance_meter") as? Number)?.toFloat(),
            altitudeGainMeter = (score?.get("altitude_gain_meter") as? Number)?.toFloat(),
            altitudeChangeMeter = (score?.get("altitude_change_meter") as? Number)?.toFloat(),
            zoneZeroMilli = (zones?.get("zone_zero_milli") as? Number)?.toLong(),
            zoneOneMilli = (zones?.get("zone_one_milli") as? Number)?.toLong(),
            zoneTwoMilli = (zones?.get("zone_two_milli") as? Number)?.toLong(),
            zoneThreeMilli = (zones?.get("zone_three_milli") as? Number)?.toLong(),
            zoneFourMilli = (zones?.get("zone_four_milli") as? Number)?.toLong(),
            zoneFiveMilli = (zones?.get("zone_five_milli") as? Number)?.toLong()
        )
    }

    private fun parseInstant(value: Any?): Instant? {
        return when (value) {
            is String -> try { Instant.parse(value) } catch (_: Exception) { null }
            else -> null
        }
    }
}
