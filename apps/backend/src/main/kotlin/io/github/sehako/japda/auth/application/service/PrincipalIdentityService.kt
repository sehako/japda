package io.github.sehako.japda.auth.application.service

import io.github.sehako.japda.auth.domain.repository.PrincipalIdentityRepository
import io.github.sehako.japda.auth.domain.repository.UserRepository
import io.github.sehako.japda.auth.exception.AuthErrorCode
import io.github.sehako.japda.global.exception.BusinessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PrincipalIdentityService(
    private val users: UserRepository,
    private val identities: PrincipalIdentityRepository,
) {
    @Transactional(readOnly = true)
    fun buyerId(userId: Long): Long {
        requireUser(userId)
        return identities.findBuyerId(userId) ?: throw BusinessException(AuthErrorCode.BUYER_LINK_REQUIRED)
    }

    @Transactional(readOnly = true)
    fun sellerId(userId: Long): Long {
        requireUser(userId)
        return identities.findSellerId(userId) ?: throw BusinessException(AuthErrorCode.SELLER_LINK_REQUIRED)
    }

    private fun requireUser(userId: Long) {
        if (users.findById(userId) == null) throw BusinessException(AuthErrorCode.UNAUTHENTICATED)
    }
}
