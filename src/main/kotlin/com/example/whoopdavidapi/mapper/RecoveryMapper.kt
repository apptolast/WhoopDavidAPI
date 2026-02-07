package com.example.whoopdavidapi.mapper

import com.example.whoopdavidapi.model.dto.RecoveryDTO
import com.example.whoopdavidapi.model.entity.WhoopRecovery
import org.mapstruct.Mapper

@Mapper(componentModel = "spring")
interface RecoveryMapper {
    fun toDto(entity: WhoopRecovery): RecoveryDTO
    fun toEntity(dto: RecoveryDTO): WhoopRecovery
}
