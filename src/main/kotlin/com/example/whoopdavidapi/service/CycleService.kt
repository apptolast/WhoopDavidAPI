package com.example.whoopdavidapi.service

import com.example.whoopdavidapi.mapper.CycleMapper
import com.example.whoopdavidapi.model.dto.CycleDTO
import com.example.whoopdavidapi.model.dto.PaginatedResponse
import com.example.whoopdavidapi.model.dto.PaginationInfo
import com.example.whoopdavidapi.repository.CycleRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class CycleService(
    private val cycleRepository: CycleRepository,
    private val cycleMapper: CycleMapper
) {

    fun getCycles(
        from: Instant?,
        to: Instant?,
        page: Int,
        pageSize: Int
    ): PaginatedResponse<CycleDTO> {
        val pageable = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "start"))

        val result = when {
            from != null && to != null -> cycleRepository.findByStartBetween(from, to, pageable)
            from != null -> cycleRepository.findByStartGreaterThanEqual(from, pageable)
            to != null -> cycleRepository.findByStartLessThan(to, pageable)
            else -> cycleRepository.findAll(pageable)
        }

        return PaginatedResponse(
            data = result.content.map { cycleMapper.toDto(it) },
            pagination = PaginationInfo(
                page = page,
                pageSize = pageSize,
                totalCount = result.totalElements,
                hasMore = result.hasNext()
            )
        )
    }
}
