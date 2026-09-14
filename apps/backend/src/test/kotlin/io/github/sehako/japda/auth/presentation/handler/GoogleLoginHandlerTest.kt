package io.github.sehako.japda.auth.presentation.handler

import io.github.sehako.japda.auth.application.service.GoogleLoginService
import io.github.sehako.japda.auth.domain.model.User
import io.github.sehako.japda.auth.domain.model.UserRole
import io.github.sehako.japda.auth.domain.repository.UserRepository
import io.github.sehako.japda.auth.domain.repository.UserRoleRepository
import io.github.sehako.japda.auth.infrastructure.token.ServiceJwtIssuer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.DisplayName
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.test.util.ReflectionTestUtils

@DisplayName("Google 로그인 결과 처리")
class GoogleLoginHandlerTest {
    private val users = MemoryUsers()
    private val service = GoogleLoginService(users, object : UserRoleRepository {
        override fun addIfAbsent(userId: Long, role: UserRole) = Unit
    }, emptySet(), Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC))
    private val jwt = ServiceJwtIssuer(SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256"), "japda", "japda-spa", Clock.systemUTC())
    private val handler = GoogleLoginSuccessHandler(service, jwt, "http://localhost:5173/auth/success", "http://localhost:5173/auth/failure", false)

    @Test
    @DisplayName("검증된 이메일은 JWT 쿠키를 설정하고 고정된 성공 주소로 이동한다")
    fun 검증된_이메일_JWT_쿠키와_성공_리다이렉트() {
        val response = MockHttpServletResponse()

        handler.onAuthenticationSuccess(MockHttpServletRequest(), response, authentication("buyer@example.com", true))

        assertEquals("http://localhost:5173/auth/success", response.redirectedUrl)
        val cookie = response.getHeader("Set-Cookie")!!
        assertContains(cookie, "HttpOnly")
        assertContains(cookie, "SameSite=Lax")
        assertContains(cookie, "Path=/api")
        assertContains(cookie, "Max-Age=3600")
        assertEquals(false, cookie.contains("Secure"))
        assertEquals(1, users.count)
    }

    @Test
    @DisplayName("검증되지 않은 이메일은 사용자와 쿠키 없이 오류 코드만 전달한다")
    fun 검증되지_않은_이메일_실패_리다이렉트() {
        val response = MockHttpServletResponse()

        handler.onAuthenticationSuccess(MockHttpServletRequest(), response, authentication("buyer@example.com", false))

        assertEquals("http://localhost:5173/auth/failure?error=EMAIL_UNVERIFIED", response.redirectedUrl)
        assertNull(response.getHeader("Set-Cookie"))
        assertEquals(0, users.count)
    }

    @Test
    @DisplayName("이메일이 없는 계정은 사용자와 쿠키 없이 실패 처리한다")
    fun 이메일_없는_계정_실패_리다이렉트() {
        val response = MockHttpServletResponse()

        handler.onAuthenticationSuccess(MockHttpServletRequest(), response, authentication(null, true))

        assertEquals("http://localhost:5173/auth/failure?error=EMAIL_UNVERIFIED", response.redirectedUrl)
        assertEquals(0, users.count)
    }

    private fun authentication(email: String?, verified: Boolean): UsernamePasswordAuthenticationToken {
        val claims = mutableMapOf<String, Any>("sub" to "google-sub-1", "email_verified" to verified)
        if (email != null) claims["email"] = email
        val token = OidcIdToken("synthetic-id-token", Instant.parse("2026-09-14T00:00:00Z"), Instant.parse("2026-09-14T01:00:00Z"), claims)
        return UsernamePasswordAuthenticationToken(DefaultOidcUser(emptyList(), token), null, emptyList())
    }

    private class MemoryUsers : UserRepository {
        var count = 0
        override fun findByGoogleSubject(subject: String): User? = null
        override fun save(user: User): User {
            ReflectionTestUtils.setField(user, "id", 1L)
            count++
            return user
        }
    }
}
