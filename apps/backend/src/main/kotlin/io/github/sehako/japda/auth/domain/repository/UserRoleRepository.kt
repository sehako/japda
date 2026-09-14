package io.github.sehako.japda.auth.domain.repository

import io.github.sehako.japda.auth.domain.model.UserRole

interface UserRoleRepository {
    fun addIfAbsent(userId: Long, role: UserRole)
    fun findByUserId(userId: Long): List<UserRole>
}
