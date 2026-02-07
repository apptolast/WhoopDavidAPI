package com.example.whoopdavidapi.controller

import com.example.whoopdavidapi.model.dto.CycleDTO
import com.example.whoopdavidapi.model.dto.PaginatedResponse
import com.example.whoopdavidapi.model.dto.PaginationInfo
import com.example.whoopdavidapi.service.CycleService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CycleControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var cycleService: CycleService

    @Test
    fun `GET cycles sin autenticacion devuelve 401`() {
        mockMvc.perform(get("/api/v1/cycles"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET cycles con Basic Auth devuelve 200`() {
        val response = PaginatedResponse(
            data = listOf(
                CycleDTO(
                    id = 1L,
                    userId = 100L,
                    createdAt = null,
                    updatedAt = null,
                    start = Instant.parse("2024-01-15T08:00:00Z"),
                    end = Instant.parse("2024-01-16T08:00:00Z"),
                    timezoneOffset = null,
                    scoreState = "SCORED",
                    strain = 15.5f,
                    kilojoule = 2500.0f,
                    averageHeartRate = 72,
                    maxHeartRate = 185
                )
            ),
            pagination = PaginationInfo(page = 1, pageSize = 100, totalCount = 1, hasMore = false)
        )

        `when`(cycleService.getCycles(null, null, 1, 100)).thenReturn(response)

        mockMvc.perform(
            get("/api/v1/cycles")
                .with(httpBasic("powerbi", "changeme"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data[0].id").value(1))
            .andExpect(jsonPath("$.data[0].strain").value(15.5))
            .andExpect(jsonPath("$.pagination.totalCount").value(1))
            .andExpect(jsonPath("$.pagination.hasMore").value(false))
    }

    @Test
    fun `GET cycles con parametros de paginacion`() {
        val response = PaginatedResponse(
            data = emptyList<CycleDTO>(),
            pagination = PaginationInfo(page = 2, pageSize = 50, totalCount = 0, hasMore = false)
        )

        `when`(cycleService.getCycles(null, null, 2, 50)).thenReturn(response)

        mockMvc.perform(
            get("/api/v1/cycles")
                .param("page", "2")
                .param("pageSize", "50")
                .with(httpBasic("powerbi", "changeme"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pagination.page").value(2))
            .andExpect(jsonPath("$.pagination.pageSize").value(50))
    }
}
