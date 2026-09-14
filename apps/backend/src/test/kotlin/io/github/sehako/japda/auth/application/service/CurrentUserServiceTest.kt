package io.github.sehako.japda.auth.application.service

import io.github.sehako.japda.auth.domain.model.User
import io.github.sehako.japda.auth.domain.model.UserRole
import io.github.sehako.japda.auth.domain.repository.UserRepository
import io.github.sehako.japda.auth.domain.repository.UserRoleRepository
import io.github.sehako.japda.auth.exception.AuthErrorCode
import io.github.sehako.japda.global.exception.BusinessException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName
import org.springframework.test.util.ReflectionTestUtils

@DisplayName("현재 사용자 조회")
class CurrentUserServiceTest {
    private val users = MemoryUsers()
    private val roles = MemoryRoles()
    private val service = CurrentUserService(users, roles)

    @Test
    @DisplayName("매 조회마다 저장된 이메일과 역할을 읽고 역할을 중복 제거하여 이름순으로 반환한다")
    fun DB_변경_반영과_역할_정렬() {
        val user = User.createGoogle("subject", "before@example.com", Instant.parse("2026-09-14T00:00:00Z"))
        ReflectionTestUtils.setField(user, "id", 123L)
        users.user = user
        roles.values = listOf(UserRole.BUYER)

        assertEquals("before@example.com", service.findCurrentUser(123L).email)
        assertEquals(listOf("BUYER"), service.findCurrentUser(123L).roles)

        user.updateEmail("after@example.com")
        roles.values = listOf(UserRole.BUYER, UserRole.ADMIN, UserRole.BUYER)
        val updated = service.findCurrentUser(123L)
        assertEquals(123L, updated.id)
        assertEquals("after@example.com", updated.email)
        assertEquals(listOf("ADMIN", "BUYER"), updated.roles)

        roles.values = listOf(UserRole.ADMIN)
        assertEquals(listOf("ADMIN"), service.findCurrentUser(123L).roles)
    }

    @Test
    @DisplayName("사용자가 삭제되면 인증 실패를 반환한다")
    fun 사용자_없음_인증_실패() {
        val error = assertFailsWith<BusinessException> { service.findCurrentUser(123L) }
        assertEquals(AuthErrorCode.UNAUTHENTICATED, error.errorCode)
    }

    @Test
    @DisplayName("DB 조회 오류는 인증 실패로 바꾸지 않는다")
    fun DB_오류_그대로_전파() {
        users.failure = IllegalStateException("database unavailable")
        val error = assertFailsWith<IllegalStateException> { service.findCurrentUser(123L) }
        assertEquals("database unavailable", error.message)
    }

    private class MemoryUsers : UserRepository {
        var user: User? = null
        var failure: RuntimeException? = null
        override fun findByGoogleSubject(subject: String): User? = user?.takeIf { it.providerSubject == subject }
        override fun findById(id: Long): User? {
            failure?.let { throw it }
            return user?.takeIf { it.id == id }
        }
        override fun save(user: User): User = user.also { this.user = it }
    }

    private class MemoryRoles : UserRoleRepository {
        var values: List<UserRole> = emptyList()
        override fun addIfAbsent(userId: Long, role: UserRole) = Unit
        override fun findByUserId(userId: Long): List<UserRole> = values
    }
}
