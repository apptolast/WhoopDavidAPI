package com.example.whoopdavidapi.mock

import org.springframework.stereotype.Component
import org.springframework.context.annotation.Profile
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.random.Random

@Component
@Profile("demo")
class MockWhoopDataGenerator {

    private val random = Random(42)
    private val userId = 123456L
    private val days = 30

    // Sport IDs reales de Whoop
    private val sportIds = listOf(0, 1, 33, 48, 52, 63, 71)
    // Running, Cycling, Yoga, Strength, CrossFit, HIIT, Swimming
    private val sportNames = listOf("Running", "Cycling", "Yoga", "Strength Training", "CrossFit", "HIIT", "Swimming")

    // Cache de datos generados (seed determinista = siempre iguales)
    val cycles: List<Map<String, Any?>> by lazy { generateCycles() }
    val recoveries: List<Map<String, Any?>> by lazy { generateRecoveries() }
    val sleeps: List<Map<String, Any?>> by lazy { generateSleeps() }
    val workouts: List<Map<String, Any?>> by lazy { generateWorkouts() }

    val profile: Map<String, Any?> = mapOf(
        "user_id" to userId,
        "email" to "david.demo@whoop.com",
        "first_name" to "David",
        "last_name" to "Demo"
    )

    private fun generateCycles(): List<Map<String, Any?>> {
        val now = Instant.now().truncatedTo(ChronoUnit.HOURS)
        return (0 until days).map { day ->
            val start = now.minus((days - day).toLong(), ChronoUnit.DAYS)
            val end = start.plus(1, ChronoUnit.DAYS)
            val strain = 4.0f + random.nextFloat() * 17.0f // 4-21
            val kilojoule = 500.0f + random.nextFloat() * 3500.0f
            val avgHr = 60 + random.nextInt(70) // 60-130
            val maxHr = 140 + random.nextInt(60) // 140-200

            mapOf(
                "id" to (1000L + day),
                "user_id" to userId,
                "created_at" to start.toString(),
                "updated_at" to end.toString(),
                "start" to start.toString(),
                "end" to end.toString(),
                "timezone_offset" to "+01:00",
                "score_state" to "SCORED",
                "score" to mapOf(
                    "strain" to strain,
                    "kilojoule" to kilojoule,
                    "average_heart_rate" to avgHr,
                    "max_heart_rate" to maxHr
                )
            )
        }
    }

    private fun generateRecoveries(): List<Map<String, Any?>> {
        return cycles.map { cycle ->
            val cycleId = cycle["id"] as Long
            val recoveryScore = 20.0f + random.nextFloat() * 79.0f // 20-99
            val restingHr = 42.0f + random.nextFloat() * 20.0f // 42-62
            val hrv = 30.0f + random.nextFloat() * 90.0f // 30-120
            val spo2 = 94.0f + random.nextFloat() * 6.0f // 94-100
            val skinTemp = 32.5f + random.nextFloat() * 2.0f // 32.5-34.5

            mapOf(
                "cycle_id" to cycleId,
                "sleep_id" to (2000L + (cycleId - 1000L)),
                "user_id" to userId,
                "created_at" to cycle["created_at"],
                "updated_at" to cycle["updated_at"],
                "score_state" to "SCORED",
                "score" to mapOf(
                    "user_calibrating" to false,
                    "recovery_score" to recoveryScore,
                    "resting_heart_rate" to restingHr,
                    "hrv_rmssd_milli" to hrv,
                    "spo2_percentage" to spo2,
                    "skin_temp_celsius" to skinTemp
                )
            )
        }
    }

    private fun generateSleeps(): List<Map<String, Any?>> {
        val now = Instant.now().truncatedTo(ChronoUnit.HOURS)
        return (0 until days).map { day ->
            val bedtime = now.minus((days - day).toLong(), ChronoUnit.DAYS)
                .minus(8, ChronoUnit.HOURS) // ~medianoche
            val totalSleepMs = ((5 + random.nextFloat() * 4) * 3600 * 1000).toLong() // 5-9h en ms
            val wakeUp = bedtime.plusMillis(totalSleepMs + (random.nextInt(30) * 60 * 1000).toLong())
            val efficiency = 0.75f + random.nextFloat() * 0.23f // 75-98%

            // Distribucion de etapas (porcentaje del total)
            val awakeMs = (totalSleepMs * (0.05 + random.nextDouble() * 0.10)).toLong()
            val remMs = (totalSleepMs * (0.15 + random.nextDouble() * 0.10)).toLong()
            val swsMs = (totalSleepMs * (0.15 + random.nextDouble() * 0.10)).toLong()
            val lightMs = totalSleepMs - awakeMs - remMs - swsMs

            mapOf(
                "id" to (2000L + day).toString(),
                "user_id" to userId,
                "created_at" to bedtime.toString(),
                "updated_at" to wakeUp.toString(),
                "start" to bedtime.toString(),
                "end" to wakeUp.toString(),
                "timezone_offset" to "+01:00",
                "nap" to false,
                "score_state" to "SCORED",
                "score" to mapOf(
                    "stage_summary" to mapOf(
                        "total_in_bed_time_milli" to totalSleepMs + awakeMs,
                        "total_awake_time_milli" to awakeMs,
                        "total_no_data_time_milli" to 0,
                        "total_light_sleep_time_milli" to lightMs,
                        "total_slow_wave_sleep_time_milli" to swsMs,
                        "total_rem_sleep_time_milli" to remMs,
                        "sleep_cycle_count" to (3 + random.nextInt(3)),
                        "disturbance_count" to random.nextInt(5)
                    ),
                    "sleep_needed" to mapOf(
                        "baseline_milli" to 28800000L, // 8h
                        "need_from_sleep_debt_milli" to (random.nextInt(3600) * 1000).toLong(),
                        "need_from_recent_strain_milli" to (random.nextInt(1800) * 1000).toLong(),
                        "need_from_recent_nap_milli" to 0L
                    ),
                    "sleep_performance_percentage" to (efficiency * 100).toInt().toFloat(),
                    "sleep_consistency_percentage" to (70 + random.nextInt(25)).toFloat(),
                    "sleep_efficiency_percentage" to (efficiency * 100),
                    "respiratory_rate" to (13.0f + random.nextFloat() * 5.0f) // 13-18
                )
            )
        }
    }

    private fun generateWorkouts(): List<Map<String, Any?>> {
        val now = Instant.now().truncatedTo(ChronoUnit.HOURS)
        val workoutList = mutableListOf<Map<String, Any?>>()

        for (day in 0 until days) {
            // 4-5 workouts/semana: ~70% probabilidad de workout por dia
            if (random.nextFloat() > 0.70f) continue

            val sportIndex = random.nextInt(sportIds.size)
            val start = now.minus((days - day).toLong(), ChronoUnit.DAYS)
                .plus(random.nextInt(4).toLong() + 7, ChronoUnit.HOURS) // 7-11 AM
            val durationMinutes = 30 + random.nextInt(60) // 30-90 min
            val end = start.plus(durationMinutes.toLong(), ChronoUnit.MINUTES)
            val strain = 6.0f + random.nextFloat() * 15.0f // 6-21
            val avgHr = 100 + random.nextInt(60) // 100-160
            val maxHr = 160 + random.nextInt(40) // 160-200
            val kilojoule = 300.0f + random.nextFloat() * 2700.0f

            // Zonas HR en milisegundos
            val totalMs = (durationMinutes * 60 * 1000).toLong()
            val zones = distributeZones(totalMs)

            workoutList.add(
                mapOf(
                    "id" to (3000L + workoutList.size).toString(),
                    "user_id" to userId,
                    "created_at" to start.toString(),
                    "updated_at" to end.toString(),
                    "start" to start.toString(),
                    "end" to end.toString(),
                    "timezone_offset" to "+01:00",
                    "sport_id" to sportIds[sportIndex],
                    "score_state" to "SCORED",
                    "score" to mapOf(
                        "strain" to strain,
                        "average_heart_rate" to avgHr,
                        "max_heart_rate" to maxHr,
                        "kilojoule" to kilojoule,
                        "percent_recorded" to 100.0f,
                        "distance_meter" to if (sportIndex <= 1) (1000.0f + random.nextFloat() * 14000.0f) else null,
                        "altitude_gain_meter" to if (sportIndex <= 1) (random.nextFloat() * 200.0f) else null,
                        "altitude_change_meter" to null,
                        "zone_duration" to mapOf(
                            "zone_zero_milli" to zones[0],
                            "zone_one_milli" to zones[1],
                            "zone_two_milli" to zones[2],
                            "zone_three_milli" to zones[3],
                            "zone_four_milli" to zones[4],
                            "zone_five_milli" to zones[5]
                        )
                    )
                )
            )
        }
        return workoutList
    }

    private fun distributeZones(totalMs: Long): List<Long> {
        // Distribucion realista: poco en zone0/5, mas en zone1-3
        val weights = listOf(0.05, 0.15, 0.30, 0.25, 0.15, 0.10)
        val zones = weights.map { (totalMs * it * (0.8 + random.nextDouble() * 0.4)).toLong() }
        return zones
    }
}
