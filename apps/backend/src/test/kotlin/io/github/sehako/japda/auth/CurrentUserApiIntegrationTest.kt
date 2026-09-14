package io.github.sehako.japda.auth

import io.github.sehako.japda.auth.infrastructure.token.ServiceJwtIssuer
import java.time.Clock
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
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
])
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("현재 로그인 사용자 API 통합")
class CurrentUserApiIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    @DisplayName("서비스 JWT 쿠키는 DB의 최신 이메일과 역할을 조회한다")
    fun 서비스_JWT_쿠키_DB_최신_이메일과_역할_조회() {
        val userId = createUser("first@example.com")
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUYER')", userId)
        val token = tokenFor(userId)
        val session = MockHttpSession()

        mockMvc.perform(get("/api/auth/me").session(session)
            .cookie(jakarta.servlet.http.Cookie("JAPDA_ACCESS_TOKEN", token)))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.id").value(userId))
            .andExpect(jsonPath("$.email").value("first@example.com"))
            .andExpect(jsonPath("$.roles[0]").value("BUYER"))

        mockMvc.perform(get("/api/auth/me").session(session))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))

        jdbcTemplate.update("UPDATE users SET email = 'latest@example.com' WHERE id = ?", userId)
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'ADMIN')", userId)

        mockMvc.perform(get("/api/auth/me").cookie(jakarta.servlet.http.Cookie("JAPDA_ACCESS_TOKEN", token)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value("latest@example.com"))
            .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
            .andExpect(jsonPath("$.roles[1]").value("BUYER"))

        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ? AND role = 'ADMIN'", userId)
        mockMvc.perform(get("/api/auth/me").cookie(jakarta.servlet.http.Cookie("JAPDA_ACCESS_TOKEN", token)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.roles[0]").value("BUYER"))
            .andExpect(jsonPath("$.roles[1]").doesNotExist())
    }

    @Test
    @DisplayName("쿠키 누락, 무효 JWT, 삭제된 사용자는 동일한 인증 실패 응답을 받는다")
    fun 쿠키_누락_무효_JWT_삭제된_사용자_동일한_인증_실패() {
        val deletedUserId = createUser("deleted@example.com")
        val deletedUserToken = tokenFor(deletedUserId)
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", deletedUserId)

        listOf(
            get("/api/auth/me"),
            get("/api/auth/me").cookie(jakarta.servlet.http.Cookie("JAPDA_ACCESS_TOKEN", "invalid.jwt.value")),
            get("/api/auth/me").cookie(jakarta.servlet.http.Cookie("JAPDA_ACCESS_TOKEN", deletedUserToken)),
        ).forEach { request ->
            mockMvc.perform(request.header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isUnauthorized)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))
        }
    }

    @Test
    @DisplayName("Google 세션과 기존 ID 헤더는 현재 사용자 API를 인증하지 않는다")
    fun Google_세션과_ID_헤더_현재_사용자_API_인증하지_않는다() {
        val context = SecurityContextHolder.createEmptyContext().apply {
            authentication = UsernamePasswordAuthenticationToken("google-user", null, emptyList())
        }
        val session = MockHttpSession().apply {
            setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context)
        }

        mockMvc.perform(get("/api/auth/me").session(session)
            .header("X-Buyer-Id", "1").header("X-Seller-Id", "1"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))

        mockMvc.perform(get("/api/products/ready").header("X-Seller-Id", "1"))
            .andExpect(status().isOk)
    }

    @Test
    @DisplayName("허용된 SPA origin의 현재 사용자 요청은 자격 증명 CORS 응답을 받는다")
    fun 허용된_SPA_origin_자격_증명_CORS_응답() {
        val userId = createUser("cors@example.com")
        val origin = "http://localhost:5173"

        mockMvc.perform(options("/api/auth/me")
            .header(HttpHeaders.ORIGIN, origin)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))

        mockMvc.perform(get("/api/auth/me")
            .header(HttpHeaders.ORIGIN, origin)
            .cookie(jakarta.servlet.http.Cookie("JAPDA_ACCESS_TOKEN", tokenFor(userId))))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
    }

    private fun createUser(email: String): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (provider, provider_subject, email, created_at) VALUES ('GOOGLE', ?, ?, now()) RETURNING id",
        Long::class.java,
        UUID.randomUUID().toString(),
        email,
    )!!

    private fun tokenFor(userId: Long): String = ServiceJwtIssuer(
        SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256"),
        "http://localhost:8080",
        "japda-spa",
        Clock.systemUTC(),
    ).issue(userId)

    companion object {
        @Container @ServiceConnection
        @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic fun properties(registry: DynamicPropertyRegistry) {
            registry.add("auth.jwt-signing-key") { Base64.getEncoder().encodeToString(ByteArray(32) { 7 }) }
        }
    }
}
