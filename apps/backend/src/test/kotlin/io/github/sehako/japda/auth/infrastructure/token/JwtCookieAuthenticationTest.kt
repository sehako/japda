package io.github.sehako.japda.auth.infrastructure.token

import io.github.sehako.japda.auth.presentation.handler.GoogleLoginSuccessHandler
import io.github.sehako.japda.auth.domain.model.User
import io.github.sehako.japda.auth.domain.repository.UserRepository
import jakarta.servlet.http.Cookie
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.spec.SecretKeySpec
import com.nimbusds.jose.jwk.source.ImmutableSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.DisplayName
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder

@DisplayName("현재 사용자 쿠키 JWT 인증")
class JwtCookieAuthenticationTest {
    private val key = SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256")
    private val users = object : UserRepository {
        override fun findByGoogleSubject(subject: String): User? = null
        override fun findById(id: Long): User? = if (id == 42L) User.createGoogle("subject", "user@example.com", Instant.EPOCH) else null
        override fun save(user: User): User = user
    }

    @Test
    @DisplayName("서비스 JWT 쿠키만 인증 시도를 만든다")
    fun 서비스_JWT_쿠키만_인증_시도() {
        val converter = JwtCookieAuthenticationConverter()
        val request = MockHttpServletRequest().apply {
            setCookies(Cookie("OTHER", "ignored"), Cookie(GoogleLoginSuccessHandler.COOKIE_NAME, "signed-token"))
        }

        assertEquals("signed-token", converter.convert(request)?.credentials)
        assertNull(converter.convert(MockHttpServletRequest().apply { addHeader("Authorization", "Bearer signed-token") }))
    }

    @Test
    @DisplayName("유효한 JWT의 내부 사용자 ID만 인증 결과에 남긴다")
    fun 유효한_JWT_사용자_ID_인증() {
        val issuer = ServiceJwtIssuer(key, "https://api.example.test", "japda-spa", Clock.fixed(Instant.now(), ZoneOffset.UTC))
        val provider = JwtCookieAuthenticationProvider(key, "https://api.example.test", "japda-spa", users)

        val authenticated = provider.authenticate(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.unauthenticated(null, issuer.issue(42)))

        assertEquals(42L, authenticated.principal)
        assertNull(authenticated.credentials)
    }

    @Test
    @DisplayName("변조한 JWT는 인증하지 않는다")
    fun 변조한_JWT_인증_거부() {
        val issuer = ServiceJwtIssuer(key, "https://api.example.test", "japda-spa", Clock.fixed(Instant.now(), ZoneOffset.UTC))
        val provider = JwtCookieAuthenticationProvider(key, "https://api.example.test", "japda-spa", users)
        val token = issuer.issue(42)
        val parts = token.split('.')
        val altered = parts[0] + "." + parts[1] + "." + (if (parts[2][0] == 'A') "B" else "A") + parts[2].drop(1)

        assertFailsWith<BadCredentialsException> {
            provider.authenticate(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.unauthenticated(null, altered))
        }
    }

    @Test
    @DisplayName("잘못된 발급자와 대상의 JWT는 인증하지 않는다")
    fun 발급자_대상_불일치_인증_거부() {
        val provider = JwtCookieAuthenticationProvider(key, "https://api.example.test", "japda-spa", users)
        listOf("https://other.example.test" to "japda-spa", "https://api.example.test" to "other-app").forEach { (issuerName, audience) ->
            val jwt = ServiceJwtIssuer(key, issuerName, audience, Clock.fixed(Instant.now(), ZoneOffset.UTC)).issue(42)
            assertFailsWith<BadCredentialsException> {
                provider.authenticate(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.unauthenticated(null, jwt))
            }
        }
    }

    @Test
    @DisplayName("만료와 exp 누락은 인증하지 않는다")
    fun 만료와_exp_누락_인증_거부() {
        val provider = JwtCookieAuthenticationProvider(key, "https://api.example.test", "japda-spa", users)
        val expired = signedToken("42", Instant.now().minusSeconds(2))
        val missingExpiration = signedToken("42", null)

        listOf(expired, missingExpiration).forEach { token ->
            assertFailsWith<BadCredentialsException> { provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(null, token)) }
        }
    }

    @Test
    @DisplayName("양의 내부 사용자 ID가 아닌 sub는 인증하지 않는다")
    fun 잘못된_sub_인증_거부() {
        val provider = JwtCookieAuthenticationProvider(key, "https://api.example.test", "japda-spa", users)
        listOf("0", "-1", "google-sub", "9223372036854775808").forEach { subject ->
            assertFailsWith<BadCredentialsException> {
                provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(null, signedToken(subject, Instant.now().plusSeconds(300))))
            }
        }
    }

    @Test
    @DisplayName("HS256이 아닌 서명 알고리즘은 인증하지 않는다")
    fun 다른_서명_알고리즘_인증_거부() {
        val longKey = SecretKeySpec(ByteArray(64) { 7 }, "HmacSHA512")
        val provider = JwtCookieAuthenticationProvider(longKey, "https://api.example.test", "japda-spa", users)
        val claims = JwtClaimsSet.builder().subject("42").issuer("https://api.example.test")
            .audience(listOf("japda-spa")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build()
        val token = NimbusJwtEncoder(ImmutableSecret(longKey))
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS512).build(), claims)).tokenValue

        assertFailsWith<BadCredentialsException> { provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(null, token)) }
    }

    private fun signedToken(subject: String, expiration: Instant?): String {
        val claims = JwtClaimsSet.builder().subject(subject).issuer("https://api.example.test")
            .audience(listOf("japda-spa")).issuedAt(Instant.now().minusSeconds(600)).apply { expiration?.let { expiresAt(it) } }.build()
        return NimbusJwtEncoder(ImmutableSecret(key))
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).tokenValue
    }

    @Test
    @DisplayName("삭제된 사용자 ID를 담은 유효한 JWT는 인증하지 않는다")
    fun 삭제된_사용자_JWT_인증_거부() {
        val provider = JwtCookieAuthenticationProvider(key, "https://api.example.test", "japda-spa", users)
        val token = ServiceJwtIssuer(key, "https://api.example.test", "japda-spa", Clock.systemUTC()).issue(43)
        assertFailsWith<BadCredentialsException> {
            provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(null, token))
        }
    }
}
