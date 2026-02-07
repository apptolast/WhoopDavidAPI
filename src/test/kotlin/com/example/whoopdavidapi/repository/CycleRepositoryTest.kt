package com.example.whoopdavidapi.repository

import com.example.whoopdavidapi.model.entity.WhoopCycle
import com.example.whoopdavidapi.util.TokenEncryptor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@DataJpaTest
@Import(TokenEncryptor::class)
@ActiveProfiles("dev")
class CycleRepositoryTest {

    @Autowired
    lateinit var cycleRepository: CycleRepository

    @Test
    fun `guardar y recuperar un ciclo`() {
        val cycle = WhoopCycle(
            id = 12345L,
            userId = 1L,
            start = Instant.parse("2024-01-15T08:00:00Z"),
            end = Instant.parse("2024-01-16T08:00:00Z"),
            scoreState = "SCORED",
            strain = 15.5f,
            kilojoule = 2500.0f,
            averageHeartRate = 72,
            maxHeartRate = 185
        )
        cycleRepository.save(cycle)

        val found = cycleRepository.findById(12345L)
        assertTrue(found.isPresent)
        assertEquals(15.5f, found.get().strain)
        assertEquals(72, found.get().averageHeartRate)
    }

    @Test
    fun `filtrar ciclos por rango de fechas`() {
        val cycle1 = WhoopCycle(id = 1L, userId = 1L, start = Instant.parse("2024-01-10T00:00:00Z"), scoreState = "SCORED")
        val cycle2 = WhoopCycle(id = 2L, userId = 1L, start = Instant.parse("2024-01-15T00:00:00Z"), scoreState = "SCORED")
        val cycle3 = WhoopCycle(id = 3L, userId = 1L, start = Instant.parse("2024-01-20T00:00:00Z"), scoreState = "SCORED")
        cycleRepository.saveAll(listOf(cycle1, cycle2, cycle3))

        val result = cycleRepository.findByStartBetween(
            Instant.parse("2024-01-12T00:00:00Z"),
            Instant.parse("2024-01-18T00:00:00Z"),
            PageRequest.of(0, 10)
        )

        assertEquals(1, result.totalElements)
        assertEquals(2L, result.content[0].id)
    }

    @Test
    fun `paginacion funciona correctamente`() {
        for (i in 1L..5L) {
            cycleRepository.save(
                WhoopCycle(id = i, userId = 1L, start = Instant.now().minusSeconds(i * 86400), scoreState = "SCORED")
            )
        }

        val page1 = cycleRepository.findAll(PageRequest.of(0, 2))
        assertEquals(2, page1.content.size)
        assertEquals(5, page1.totalElements)
        assertTrue(page1.hasNext())
    }
}
