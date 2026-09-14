package io.github.sehako.japda.auth.application.service

import io.github.sehako.japda.auth.application.response.CurrentUserResponse
import io.github.sehako.japda.auth.domain.repository.UserRepository
import io.github.sehako.japda.auth.domain.repository.UserRoleRepository
import io.github.sehako.japda.auth.exception.AuthErrorCode
import io.github.sehako.japda.global.exception.BusinessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CurrentUserService(
    private val users: UserRepository,
    private val roles: UserRoleRepository,
) {
    @Transactional(readOnly = true)
    fun findCurrentUser(userId: Long): CurrentUserResponse {
        val user = users.findById(userId) ?: throw BusinessException(AuthErrorCode.UNAUTHENTICATED)
        return CurrentUserResponse(
            id = checkNotNull(user.id),
            email = user.email,
            roles = roles.findByUserId(userId).map { it.name }.distinct().sorted(),
        )
    }
}
