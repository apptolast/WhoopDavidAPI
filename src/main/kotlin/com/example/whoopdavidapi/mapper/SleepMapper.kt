package com.example.whoopdavidapi.mapper

import com.example.whoopdavidapi.model.dto.SleepDTO
import com.example.whoopdavidapi.model.entity.WhoopSleep
import org.mapstruct.Mapper

@Mapper(componentModel = "spring")
interface SleepMapper {
    fun toDto(entity: WhoopSleep): SleepDTO
    fun toEntity(dto: SleepDTO): WhoopSleep
}
