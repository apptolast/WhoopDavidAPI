package com.example.whoopdavidapi.service

import com.example.whoopdavidapi.mapper.CycleMapper
import com.example.whoopdavidapi.model.dto.CycleDTO
import com.example.whoopdavidapi.model.entity.WhoopCycle
import com.example.whoopdavidapi.repository.CycleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class CycleServiceTest {

    @Mock
    lateinit var cycleRepository: CycleRepository

    @Mock
    lateinit var cycleMapper: CycleMapper

    @InjectMocks
    lateinit var cycleService: CycleService

    @Test
    fun `getCycles sin filtros devuelve todos los ciclos paginados`() {
        val entity = WhoopCycle(
            id = 1L,
            userId = 100L,
            start = Instant.parse("2024-01-15T08:00:00Z"),
            scoreState = "SCORED",
            strain = 15.5f
        )
        val dto = CycleDTO(
            id = 1L,
            userId = 100L,
            createdAt = null,
            updatedAt = null,
            start = Instant.parse("2024-01-15T08:00:00Z"),
            end = null,
            timezoneOffset = null,
            scoreState = "SCORED",
            strain = 15.5f,
            kilojoule = null,
            averageHeartRate = null,
            maxHeartRate = null
        )

        val pageable = PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "start"))
        val page = PageImpl(listOf(entity), pageable, 1)

        `when`(cycleRepository.findAll(pageable)).thenReturn(page)
        `when`(cycleMapper.toDto(entity)).thenReturn(dto)

        val result = cycleService.getCycles(null, null, 1, 100)

        assertEquals(1, result.data.size)
        assertEquals(1L, result.data[0].id)
        assertEquals(15.5f, result.data[0].strain)
        assertEquals(1, result.pagination.page)
        assertEquals(false, result.pagination.hasMore)
    }

    @Test
    fun `getCycles con filtro from usa findByStartGreaterThanEqual`() {
        val from = Instant.parse("2024-01-10T00:00:00Z")
        val pageable = PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "start"))
        val emptyPage = PageImpl(emptyList<WhoopCycle>(), pageable, 0)

        `when`(cycleRepository.findByStartGreaterThanEqual(from, pageable)).thenReturn(emptyPage)

        val result = cycleService.getCycles(from, null, 1, 100)

        assertEquals(0, result.data.size)
        assertEquals(0, result.pagination.totalCount)
    }
}
