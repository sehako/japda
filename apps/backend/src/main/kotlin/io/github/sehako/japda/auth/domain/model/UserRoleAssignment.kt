package io.github.sehako.japda.auth.domain.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable

@Entity
@Table(name = "user_roles")
@IdClass(UserRoleAssignmentId::class)
class UserRoleAssignment(
    @field:Id
    @field:Column(name = "user_id", nullable = false)
    val userId: Long,
    @field:Id
    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 20)
    val role: UserRole,
)

data class UserRoleAssignmentId(
    val userId: Long = 0,
    val role: UserRole = UserRole.BUYER,
) : Serializable
