package io.github.sehako.japda.auth.infrastructure.config

import io.github.sehako.japda.auth.application.service.GoogleLoginService
import io.github.sehako.japda.auth.application.service.PrincipalIdentityService
import io.github.sehako.japda.auth.domain.model.User
import io.github.sehako.japda.auth.domain.repository.UserRepository
import io.github.sehako.japda.auth.exception.AuthErrorCode
import io.github.sehako.japda.auth.infrastructure.token.ServiceJwtIssuer
import io.github.sehako.japda.global.error.ProblemDetailFactory
import io.github.sehako.japda.global.exception.BusinessException
import io.github.sehako.japda.order.application.service.BuyerOrderHistoryService
import io.github.sehako.japda.order.application.service.OrderService
import io.github.sehako.japda.order.presentation.controller.OrderController
import jakarta.servlet.http.Cookie
import java.time.Clock
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@DisplayName("인증 보안 설정")
@WebMvcTest(
    controllers = [OrderController::class],
    excludeAutoConfiguration = [OAuth2ClientWebSecurityAutoConfiguration::class],
    properties = [
        "auth.jwt-signing-key=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
        "auth.cookie-secure=false",
    ],
)
@Import(AuthSecurityConfig::class, ProblemDetailFactory::class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration::class)
class AuthSecurityConfigTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var googleLoginService: GoogleLoginService

    @MockitoBean
    private lateinit var orderService: OrderService

    @MockitoBean
    private lateinit var buyerOrderHistoryService: BuyerOrderHistoryService

    @MockitoBean
    private lateinit var principalIdentityService: PrincipalIdentityService

    @MockitoBean
    private lateinit var users: UserRepository

    @MockitoBean
    private lateinit var clock: Clock

    @BeforeEach
    fun setUp() {
        `when`(users.findById(17L)).thenReturn(mock(User::class.java))
        doThrow(BusinessException(AuthErrorCode.BUYER_LINK_REQUIRED)).`when`(principalIdentityService).buyerId(17L)
    }

    @Test
    @DisplayName("주문 내역 조회는 서비스 JWT 쿠키로 인증한다")
    fun 주문_내역_조회_서비스_JWT_쿠키_인증() {
        val key = SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256")
        val token = ServiceJwtIssuer(key, "http://localhost:8080", "japda-spa", Clock.systemUTC()).issue(17L)

        mockMvc.perform(get("/api/orders").cookie(Cookie("JAPDA_ACCESS_TOKEN", token)))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("AUTH_BUYER_LINK_REQUIRED"))
    }
}
