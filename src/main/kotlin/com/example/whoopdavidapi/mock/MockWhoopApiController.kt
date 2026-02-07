package com.example.whoopdavidapi.mock

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@Profile("demo")
@RequestMapping("/mock/developer/v1")
class MockWhoopApiController(
    private val dataGenerator: MockWhoopDataGenerator
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/cycle")
    fun getCycles(
        @RequestParam(defaultValue = "25") limit: Int,
        @RequestParam(required = false) nextToken: String?,
        @RequestParam(required = false) start: String?,
        @RequestParam(required = false) end: String?
    ): Map<String, Any?> {
        log.debug("Mock GET /cycle limit={} nextToken={}", limit, nextToken)
        return paginate(dataGenerator.cycles, limit, nextToken)
    }

    @GetMapping("/recovery")
    fun getRecoveries(
        @RequestParam(defaultValue = "25") limit: Int,
        @RequestParam(required = false) nextToken: String?,
        @RequestParam(required = false) start: String?,
        @RequestParam(required = false) end: String?
    ): Map<String, Any?> {
        log.debug("Mock GET /recovery limit={} nextToken={}", limit, nextToken)
        return paginate(dataGenerator.recoveries, limit, nextToken)
    }

    @GetMapping("/activity/sleep")
    fun getSleeps(
        @RequestParam(defaultValue = "25") limit: Int,
        @RequestParam(required = false) nextToken: String?,
        @RequestParam(required = false) start: String?,
        @RequestParam(required = false) end: String?
    ): Map<String, Any?> {
        log.debug("Mock GET /activity/sleep limit={} nextToken={}", limit, nextToken)
        return paginate(dataGenerator.sleeps, limit, nextToken)
    }

    @GetMapping("/activity/workout")
    fun getWorkouts(
        @RequestParam(defaultValue = "25") limit: Int,
        @RequestParam(required = false) nextToken: String?,
        @RequestParam(required = false) start: String?,
        @RequestParam(required = false) end: String?
    ): Map<String, Any?> {
        log.debug("Mock GET /activity/workout limit={} nextToken={}", limit, nextToken)
        return paginate(dataGenerator.workouts, limit, nextToken)
    }

    @GetMapping("/user/profile/basic")
    fun getProfile(): Map<String, Any?> {
        log.debug("Mock GET /user/profile/basic")
        return dataGenerator.profile
    }

    private fun paginate(
        allRecords: List<Map<String, Any?>>,
        limit: Int,
        nextToken: String?
    ): Map<String, Any?> {
        val offset = nextToken?.toIntOrNull() ?: 0
        val effectiveLimit = limit.coerceIn(1, 25)
        val page = allRecords.drop(offset).take(effectiveLimit)
        val nextOffset = offset + page.size

        return mapOf(
            "records" to page,
            "next_token" to if (nextOffset < allRecords.size) nextOffset.toString() else null
        )
    }
}
