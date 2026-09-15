package io.github.sehako.japda.auth.application.service

import io.github.sehako.japda.auth.domain.model.User
import io.github.sehako.japda.auth.domain.repository.PrincipalIdentityRepository
import io.github.sehako.japda.auth.domain.repository.UserRepository
import io.github.sehako.japda.auth.exception.AuthErrorCode
import io.github.sehako.japda.global.exception.BusinessException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@DisplayName("인증 주체의 도메인 식별자 연결")
class PrincipalIdentityServiceTest {
    private val users = object : UserRepository {
        override fun findByGoogleSubject(subject: String): User? = null
        override fun findById(id: Long): User? = if (id == 10L || id == 20L) User.createGoogle("subject", "user@example.com", java.time.Instant.EPOCH) else null
        override fun save(user: User): User = user
    }
    private val links = object : PrincipalIdentityRepository {
        override fun findBuyerId(userId: Long): Long? = if (userId == 10L) 98L else null
        override fun findSellerId(userId: Long): Long? = if (userId == 10L) 42L else null
        override fun createBuyerLink(userId: Long): Long = error("사용하지 않음")
    }
    private val service = PrincipalIdentityService(users, links)

    @Test
    @DisplayName("사용자 ID와 다른 연결된 도메인 ID를 반환한다")
    fun 연결된_도메인_ID_반환() {
        assertEquals(98L, service.buyerId(10L))
        assertEquals(42L, service.sellerId(10L))
    }

    @Test
    @DisplayName("삭제된 사용자는 연결 유무와 무관하게 인증 실패한다")
    fun 삭제된_사용자_인증_실패() {
        assertEquals(AuthErrorCode.UNAUTHENTICATED, assertFailsWith<BusinessException> { service.buyerId(11L) }.errorCode)
    }

    @Test
    @DisplayName("연결되지 않은 사용자는 도메인별 접근 거부를 받는다")
    fun 미연결_사용자_접근_거부() {
        assertEquals(AuthErrorCode.BUYER_LINK_REQUIRED, assertFailsWith<BusinessException> { service.buyerId(20L) }.errorCode)
        assertEquals(AuthErrorCode.SELLER_LINK_REQUIRED, assertFailsWith<BusinessException> { service.sellerId(20L) }.errorCode)
    }
}
