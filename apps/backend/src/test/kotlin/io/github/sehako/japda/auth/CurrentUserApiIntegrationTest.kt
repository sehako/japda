package io.github.sehako.japda.auth

import io.github.sehako.japda.auth.infrastructure.token.ServiceJwtIssuer
import java.time.Clock
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
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
import org.springframework.web.context.WebApplicationContext
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import jakarta.servlet.Filter
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.RestDocumentationExtension
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessRequest
import org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse
import org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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
@ExtendWith(RestDocumentationExtension::class)
@DisplayName("현재 로그인 사용자 API 통합")
class CurrentUserApiIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var webContext: WebApplicationContext
    private lateinit var documentedMockMvc: MockMvc

    @BeforeEach
    fun configureDocumentation(restDocumentation: RestDocumentationContextProvider) {
        val builder: DefaultMockMvcBuilder = MockMvcBuilders.webAppContextSetup(webContext)
        builder.addFilters<DefaultMockMvcBuilder>(webContext.getBean("springSecurityFilterChain", Filter::class.java))
        documentedMockMvc = builder.apply<DefaultMockMvcBuilder>(documentationConfiguration(restDocumentation))
            .build()
    }

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
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))
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

        mockMvc.perform(options("/api/products")
            .header(HttpHeaders.ORIGIN, origin)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-CSRF-TOKEN"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "X-CSRF-TOKEN"))
    }

    @Test
    @DisplayName("CSRF 조회 응답의 토큰과 HttpOnly 쿠키로 상태 변경 요청을 검증한다")
    fun CSRF_토큰_발급과_검증() {
        val userId = createUser("csrf@example.com")
        val accessCookie = jakarta.servlet.http.Cookie("JAPDA_ACCESS_TOKEN", tokenFor(userId))
        val result = mockMvc.perform(get("/api/auth/csrf").cookie(accessCookie))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
            .andReturn()
        val token = com.jayway.jsonpath.JsonPath.read<String>(result.response.contentAsString, "$.token")
        val csrfCookie = result.response.cookies.first { it.name == "XSRF-TOKEN" }
        kotlin.test.assertTrue(result.response.getHeaders(HttpHeaders.SET_COOKIE).any { it.contains("XSRF-TOKEN=") && it.contains("HttpOnly") && it.contains("Path=/api") })

        mockMvc.perform(post("/api/products").cookie(accessCookie, csrfCookie).header("X-CSRF-TOKEN", token)
            .contentType("application/json").content("{\"name\":\"검증 상품\"}"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("AUTH_SELLER_LINK_REQUIRED"))
        mockMvc.perform(post("/api/products").cookie(accessCookie, csrfCookie))
            .andExpect(status().isForbidden)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"))
        mockMvc.perform(post("/api/products").cookie(accessCookie, csrfCookie).header("X-CSRF-TOKEN", "wrong-token"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"))
        mockMvc.perform(post("/api/products").cookie(csrfCookie).header("X-CSRF-TOKEN", token))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))
    }

    @Test
    @DisplayName("무효 JWT와 삭제된 사용자는 CSRF 토큰 유무와 관계없이 401을 받는다")
    fun 무효_JWT와_삭제_사용자_CSRF보다_인증_실패_우선() {
        val userId = createUser("deleted-csrf@example.com")
        val validCookie = jakarta.servlet.http.Cookie("JAPDA_ACCESS_TOKEN", tokenFor(userId))
        val result = mockMvc.perform(get("/api/auth/csrf").cookie(validCookie)).andExpect(status().isOk).andReturn()
        val csrfCookie = result.response.cookies.first { it.name == "XSRF-TOKEN" }
        val token = com.jayway.jsonpath.JsonPath.read<String>(result.response.contentAsString, "$.token")
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId)

        listOf(
            post("/api/products").cookie(validCookie, csrfCookie).header("X-CSRF-TOKEN", token),
            post("/api/products").cookie(validCookie),
            post("/api/products").cookie(jakarta.servlet.http.Cookie("JAPDA_ACCESS_TOKEN", "invalid.jwt"), csrfCookie).header("X-CSRF-TOKEN", token),
        ).forEach { request ->
            mockMvc.perform(request)
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))
        }
    }

    @Test
    @DisplayName("인증된 상태 변경 요청의 누락된 CSRF 토큰은 403 ProblemDetail로 문서화한다")
    fun CSRF_검증_실패_문서화() {
        val userId = createUser("csrf-doc@example.com")
        documentedMockMvc.perform(post("/api/products")
            .cookie(jakarta.servlet.http.Cookie("JAPDA_ACCESS_TOKEN", tokenFor(userId))))
            .andExpect(status().isForbidden)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"))
            .andDo(document(
                "auth-csrf-invalid",
                preprocessRequest(prettyPrint()),
                preprocessResponse(prettyPrint()),
                responseHeaders(
                    headerWithName("Content-Type").description("application/problem+json"),
                    headerWithName("Cache-Control").description("no-store"),
                ),
                responseFields(
                    fieldWithPath("type").description("문제 유형"),
                    fieldWithPath("title").description("접근 거부 제목"),
                    fieldWithPath("status").description("HTTP 상태 코드"),
                    fieldWithPath("detail").description("CSRF 검증 실패 설명"),
                    fieldWithPath("instance").description("요청 경로"),
                    fieldWithPath("code").description("AUTH_CSRF_INVALID 오류 코드"),
                ),
            ))
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
