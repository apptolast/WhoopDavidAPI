package com.example.whoopdavidapi.model.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "whoop_sleeps")
class WhoopSleep(
    @Id
    @Column(name = "id")
    var id: String = "",

    @Column(name = "cycle_id")
    var cycleId: Long? = null,

    @Column(name = "user_id", nullable = false)
    var userId: Long = 0,

    @Column(name = "created_at")
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null,

    @Column(name = "start_time", nullable = false)
    var start: Instant = Instant.now(),

    @Column(name = "end_time")
    var end: Instant? = null,

    @Column(name = "timezone_offset")
    var timezoneOffset: String? = null,

    @Column(name = "nap")
    var nap: Boolean = false,

    @Column(name = "score_state", nullable = false)
    var scoreState: String = "PENDING_SCORE",

    // SleepStageSummary fields (flattened)
    @Column(name = "total_in_bed_time_milli")
    var totalInBedTimeMilli: Int? = null,

    @Column(name = "total_awake_time_milli")
    var totalAwakeTimeMilli: Int? = null,

    @Column(name = "total_no_data_time_milli")
    var totalNoDataTimeMilli: Int? = null,

    @Column(name = "total_light_sleep_time_milli")
    var totalLightSleepTimeMilli: Int? = null,

    @Column(name = "total_slow_wave_sleep_time_milli")
    var totalSlowWaveSleepTimeMilli: Int? = null,

    @Column(name = "total_rem_sleep_time_milli")
    var totalRemSleepTimeMilli: Int? = null,

    @Column(name = "sleep_cycle_count")
    var sleepCycleCount: Int? = null,

    @Column(name = "disturbance_count")
    var disturbanceCount: Int? = null,

    // SleepNeeded fields (flattened)
    @Column(name = "baseline_milli")
    var baselineMilli: Long? = null,

    @Column(name = "need_from_sleep_debt_milli")
    var needFromSleepDebtMilli: Long? = null,

    @Column(name = "need_from_recent_strain_milli")
    var needFromRecentStrainMilli: Long? = null,

    @Column(name = "need_from_recent_nap_milli")
    var needFromRecentNapMilli: Long? = null,

    // Score fields
    @Column(name = "respiratory_rate")
    var respiratoryRate: Float? = null,

    @Column(name = "sleep_performance_percentage")
    var sleepPerformancePercentage: Float? = null,

    @Column(name = "sleep_consistency_percentage")
    var sleepConsistencyPercentage: Float? = null,

    @Column(name = "sleep_efficiency_percentage")
    var sleepEfficiencyPercentage: Float? = null
)
