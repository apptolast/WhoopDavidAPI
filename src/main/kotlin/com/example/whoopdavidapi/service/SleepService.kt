package com.example.whoopdavidapi.service

import com.example.whoopdavidapi.mapper.SleepMapper
import com.example.whoopdavidapi.model.dto.PaginatedResponse
import com.example.whoopdavidapi.model.dto.PaginationInfo
import com.example.whoopdavidapi.model.dto.SleepDTO
import com.example.whoopdavidapi.repository.SleepRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class SleepService(
    private val sleepRepository: SleepRepository,
    private val sleepMapper: SleepMapper
) {

    fun getSleeps(
        from: Instant?,
        to: Instant?,
        page: Int,
        pageSize: Int
    ): PaginatedResponse<SleepDTO> {
        val pageable = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "start"))

        val result = when {
            from != null && to != null -> sleepRepository.findByStartBetween(from, to, pageable)
            from != null -> sleepRepository.findByStartGreaterThanEqual(from, pageable)
            to != null -> sleepRepository.findByStartLessThan(to, pageable)
            else -> sleepRepository.findAll(pageable)
        }

        return PaginatedResponse(
            data = result.content.map { sleepMapper.toDto(it) },
            pagination = PaginationInfo(
                page = page,
                pageSize = pageSize,
                totalCount = result.totalElements,
                hasMore = result.hasNext()
            )
        )
    }
}
