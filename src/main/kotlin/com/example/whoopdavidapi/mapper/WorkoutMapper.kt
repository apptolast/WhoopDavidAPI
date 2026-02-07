package com.example.whoopdavidapi.mapper

import com.example.whoopdavidapi.model.dto.WorkoutDTO
import com.example.whoopdavidapi.model.entity.WhoopWorkout
import org.mapstruct.Mapper

@Mapper(componentModel = "spring")
interface WorkoutMapper {
    fun toDto(entity: WhoopWorkout): WorkoutDTO
    fun toEntity(dto: WorkoutDTO): WhoopWorkout
}
