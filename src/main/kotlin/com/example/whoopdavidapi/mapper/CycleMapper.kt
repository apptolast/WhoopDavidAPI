package com.example.whoopdavidapi.mapper

import com.example.whoopdavidapi.model.dto.CycleDTO
import com.example.whoopdavidapi.model.entity.WhoopCycle
import org.mapstruct.Mapper

@Mapper(componentModel = "spring")
interface CycleMapper {
    fun toDto(entity: WhoopCycle): CycleDTO
    fun toEntity(dto: CycleDTO): WhoopCycle
}
