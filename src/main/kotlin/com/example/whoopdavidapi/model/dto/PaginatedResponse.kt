package com.example.whoopdavidapi.model.dto

data class PaginatedResponse<T>(
    val data: List<T>,
    val pagination: PaginationInfo
)

data class PaginationInfo(
    val page: Int,
    val pageSize: Int,
    val totalCount: Long,
    val hasMore: Boolean
)
