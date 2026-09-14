package io.github.sehako.japda.auth.infrastructure.persistence

import io.github.sehako.japda.auth.domain.model.UserRole
import io.github.sehako.japda.auth.domain.model.UserRoleAssignment
import io.github.sehako.japda.auth.domain.repository.UserRoleRepository
import org.springframework.stereotype.Repository

@Repository
class UserRoleRepositoryImpl(private val jpaRepository: UserRoleJpaRepository) : UserRoleRepository {
    override fun addIfAbsent(userId: Long, role: UserRole) {
        jpaRepository.save(UserRoleAssignment(userId, role))
    }

    override fun findByUserId(userId: Long): List<UserRole> =
        jpaRepository.findAllByUserId(userId).map(UserRoleAssignment::role)
}
