package io.github.sehako.japda.auth

import io.github.sehako.japda.auth.application.service.GoogleLoginService
import io.github.sehako.japda.auth.infrastructure.token.ServiceJwtIssuer
import io.github.sehako.japda.auth.presentation.handler.GoogleLoginSuccessHandler
import java.time.Clock
import java.time.Instant
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(properties = [
    "product.image.s3.region=ap-northeast-2",
    "product.image.s3.bucket=test-product-images",
    "spring.security.oauth2.client.registration.google.client-id=synthetic-client-id",
    "spring.security.oauth2.client.registration.google.client-secret=synthetic-client-secret",
    "auth.cookie-secure=false",
    "auth.admin-emails=admin@gmail.com",
])
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Google OAuth2 로그인 흐름")
class GoogleOAuthFlowIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var loginService: GoogleLoginService
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    @DisplayName("Google 인가 경로는 state를 세션에 저장하고 Google로 이동한다")
    fun Google_인가_경로_state_세션_저장() {
        val result = mockMvc.perform(get("/oauth2/authorization/google"))
            .andExpect(status().is3xxRedirection)
            .andReturn()

        assertContains(result.response.redirectedUrl!!, "accounts.google.com")
        assertContains(result.response.redirectedUrl!!, "state=")
        assertNotNull(result.request.session)
    }

    @Test
    @DisplayName("Google 실패 콜백은 비밀값 없이 고정된 실패 주소로 이동한다")
    fun Google_실패_콜백_오류_코드만_전달() {
        val start = mockMvc.perform(get("/oauth2/authorization/google")).andReturn()
        val state = java.net.URI(start.response.redirectedUrl).rawQuery.split('&')
            .first { it.startsWith("state=") }.substringAfter('=')

        mockMvc.perform(get("/login/oauth2/code/google").session(start.request.session as org.springframework.mock.web.MockHttpSession)
            .param("state", state).param("error", "access_denied"))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("http://localhost:5173/auth/failure?error=GOOGLE_LOGIN_FAILED"))
    }

    @Test
    @DisplayName("기존 도메인 API는 세션 없이 기존 ID 헤더로 접근한다")
    fun 기존_API_ID_헤더_계약_유지() {
        mockMvc.perform(get("/api/products/ready").header("X-Seller-Id", "1"))
            .andExpect(status().isOk)
    }

    @Test
    @DisplayName("모의 OIDC 성공은 Google sub별 사용자를 저장하고 이메일과 역할을 갱신한다")
    fun 모의_OIDC_성공_사용자와_역할_저장() {
        val issuer = ServiceJwtIssuer(SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256"),
            "http://localhost:8080", "japda-spa", Clock.systemUTC())
        val handler = GoogleLoginSuccessHandler(loginService, issuer,
            "http://localhost:5173/auth/success", "http://localhost:5173/auth/failure", false)

        fun login(subject: String, email: String): MockHttpServletResponse {
            val claims = mapOf("sub" to subject, "email" to email, "email_verified" to true)
            val token = OidcIdToken("synthetic-id-token", Instant.now(), Instant.now().plusSeconds(3600), claims)
            val authentication = UsernamePasswordAuthenticationToken(DefaultOidcUser(emptyList(), token), null, emptyList())
            return MockHttpServletResponse().also { handler.onAuthenticationSuccess(MockHttpServletRequest(), it, authentication) }
        }

        assertEquals("http://localhost:5173/auth/success", login("sub-1", "first@example.com").redirectedUrl)
        assertEquals("http://localhost:5173/auth/success", login("sub-1", "admin@gmail.com").redirectedUrl)
        assertEquals("http://localhost:5173/auth/success", login("sub-2", "admin@gmail.com").redirectedUrl)

        assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM users", Int::class.java))
        assertEquals("admin@gmail.com", jdbcTemplate.queryForObject(
            "SELECT email FROM users WHERE provider = 'GOOGLE' AND provider_subject = 'sub-1'", String::class.java))
        assertEquals(2, jdbcTemplate.queryForObject(
            "SELECT count(*) FROM user_roles WHERE role = 'BUYER'", Int::class.java))
        assertEquals(2, jdbcTemplate.queryForObject(
            "SELECT count(*) FROM user_roles WHERE role = 'ADMIN'", Int::class.java))
    }

    companion object {
        @Container @ServiceConnection
        @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic fun properties(registry: DynamicPropertyRegistry) {
            registry.add("auth.jwt-signing-key") { Base64.getEncoder().encodeToString(ByteArray(32) { 7 }) }
        }
    }
}
