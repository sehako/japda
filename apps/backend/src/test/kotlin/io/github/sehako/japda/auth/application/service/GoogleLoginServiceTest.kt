package io.github.sehako.japda.auth.application.service

import io.github.sehako.japda.auth.domain.model.User
import io.github.sehako.japda.auth.domain.model.UserRole
import io.github.sehako.japda.auth.domain.repository.UserRepository
import io.github.sehako.japda.auth.domain.repository.UserRoleRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.springframework.test.util.ReflectionTestUtils

@DisplayName("Google 로그인 사용자 저장")
class GoogleLoginServiceTest {
    private val users = MemoryUserRepository()
    private val roles = MemoryUserRoleRepository()
    private val service = GoogleLoginService(users, roles, setOf("admin@gmail.com"), Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC))

    @Test
    @DisplayName("첫 로그인은 사용자를 생성하고 BUYER 역할을 부여한다")
    fun 첫_로그인_사용자와_BUYER_역할_생성() {
        val id = service.login("google-sub-1", "buyer@example.com")

        assertEquals("buyer@example.com", users.findByGoogleSubject("google-sub-1")?.email)
        assertEquals(setOf(UserRole.BUYER), roles.roles(id))
    }

    @Test
    @DisplayName("같은 sub의 재로그인은 이메일을 갱신하고 사용자와 역할을 중복 생성하지 않는다")
    fun 같은_sub_재로그인_이메일_갱신과_중복_방지() {
        val first = service.login("google-sub-1", "first@example.com")
        val second = service.login("google-sub-1", "changed@example.com")

        assertEquals(first, second)
        assertEquals(1, users.count())
        assertEquals("changed@example.com", users.findByGoogleSubject("google-sub-1")?.email)
        assertEquals(setOf(UserRole.BUYER), roles.roles(first))
    }

    @Test
    @DisplayName("서로 다른 sub는 같은 이메일이어도 별도 사용자로 저장한다")
    fun 다른_sub_같은_이메일_사용자_분리() {
        val first = service.login("google-sub-1", "same@example.com")
        val second = service.login("google-sub-2", "same@example.com")

        assertNotEquals(first, second)
        assertEquals(2, users.count())
    }

    @Test
    @DisplayName("관리자 이메일은 대소문자와 관계없이 ADMIN 역할을 한 번만 저장한다")
    fun 관리자_이메일_ADMIN_역할_중복_방지() {
        val id = service.login("google-sub-1", "ADMIN@GMAIL.COM")
        service.login("google-sub-1", "admin@gmail.com")

        assertEquals(setOf(UserRole.BUYER, UserRole.ADMIN), roles.roles(id))
    }

    private class MemoryUserRepository : UserRepository {
        private val users = mutableMapOf<String, User>()
        private var nextId = 1L

        override fun findByGoogleSubject(subject: String): User? = users[subject]

        override fun findById(id: Long): User? = users.values.firstOrNull { it.id == id }

        override fun save(user: User): User {
            if (user.id == null) ReflectionTestUtils.setField(user, "id", nextId++)
            users[user.providerSubject] = user
            return user
        }

        fun count(): Int = users.size
    }

    private class MemoryUserRoleRepository : UserRoleRepository {
        private val values = mutableMapOf<Long, MutableSet<UserRole>>()

        override fun addIfAbsent(userId: Long, role: UserRole) {
            values.getOrPut(userId) { mutableSetOf() }.add(role)
        }

        override fun findByUserId(userId: Long): List<UserRole> = values[userId].orEmpty().toList()

        fun roles(userId: Long): Set<UserRole> = values[userId].orEmpty()
    }
}
