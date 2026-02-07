package com.example.whoopdavidapi.service

import com.example.whoopdavidapi.mapper.RecoveryMapper
import com.example.whoopdavidapi.model.dto.PaginatedResponse
import com.example.whoopdavidapi.model.dto.PaginationInfo
import com.example.whoopdavidapi.model.dto.RecoveryDTO
import com.example.whoopdavidapi.repository.RecoveryRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class RecoveryService(
    private val recoveryRepository: RecoveryRepository,
    private val recoveryMapper: RecoveryMapper
) {

    fun getRecoveries(
        from: Instant?,
        to: Instant?,
        page: Int,
        pageSize: Int
    ): PaginatedResponse<RecoveryDTO> {
        val pageable = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"))

        val result = when {
            from != null && to != null -> recoveryRepository.findByCreatedAtBetween(from, to, pageable)
            from != null -> recoveryRepository.findByCreatedAtGreaterThanEqual(from, pageable)
            to != null -> recoveryRepository.findByCreatedAtLessThan(to, pageable)
            else -> recoveryRepository.findAll(pageable)
        }

        return PaginatedResponse(
            data = result.content.map { recoveryMapper.toDto(it) },
            pagination = PaginationInfo(
                page = page,
                pageSize = pageSize,
                totalCount = result.totalElements,
                hasMore = result.hasNext()
            )
        )
    }
}
