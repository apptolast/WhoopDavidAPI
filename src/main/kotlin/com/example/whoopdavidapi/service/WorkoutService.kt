package com.example.whoopdavidapi.service

import com.example.whoopdavidapi.mapper.WorkoutMapper
import com.example.whoopdavidapi.model.dto.PaginatedResponse
import com.example.whoopdavidapi.model.dto.PaginationInfo
import com.example.whoopdavidapi.model.dto.WorkoutDTO
import com.example.whoopdavidapi.repository.WorkoutRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class WorkoutService(
    private val workoutRepository: WorkoutRepository,
    private val workoutMapper: WorkoutMapper
) {

    fun getWorkouts(
        from: Instant?,
        to: Instant?,
        page: Int,
        pageSize: Int
    ): PaginatedResponse<WorkoutDTO> {
        val pageable = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "start"))

        val result = when {
            from != null && to != null -> workoutRepository.findByStartBetween(from, to, pageable)
            from != null -> workoutRepository.findByStartGreaterThanEqual(from, pageable)
            to != null -> workoutRepository.findByStartLessThan(to, pageable)
            else -> workoutRepository.findAll(pageable)
        }

        return PaginatedResponse(
            data = result.content.map { workoutMapper.toDto(it) },
            pagination = PaginationInfo(
                page = page,
                pageSize = pageSize,
                totalCount = result.totalElements,
                hasMore = result.hasNext()
            )
        )
    }
}
