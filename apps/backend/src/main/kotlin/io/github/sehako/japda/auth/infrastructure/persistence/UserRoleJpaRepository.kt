package io.github.sehako.japda.auth.infrastructure.persistence

import io.github.sehako.japda.auth.domain.model.UserRoleAssignment
import io.github.sehako.japda.auth.domain.model.UserRoleAssignmentId
import org.springframework.data.jpa.repository.JpaRepository

interface UserRoleJpaRepository : JpaRepository<UserRoleAssignment, UserRoleAssignmentId>
