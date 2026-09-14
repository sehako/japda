package io.github.sehako.japda.auth.infrastructure.token

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.DisplayName
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult

@DisplayName("서비스 JWT 발급")
class ServiceJwtIssuerTest {
    @Test
    @DisplayName("내부 사용자 ID와 발급자와 대상과 1시간 만료를 서명된 JWT에 담고 역할은 제외한다")
    fun 사용자_ID_발급자_대상_만료와_역할_제외() {
        val key = SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256")
        val issuer = ServiceJwtIssuer(key, "https://api.example.test", "japda-spa", Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC))

        val decoder = NimbusJwtDecoder.withSecretKey(key).build()
        decoder.setJwtValidator { OAuth2TokenValidatorResult.success() }
        val jwt = decoder.decode(issuer.issue(42L))

        assertEquals("42", jwt.subject)
        assertEquals("https://api.example.test", jwt.issuer.toString())
        assertEquals(listOf("japda-spa"), jwt.audience)
        assertEquals(Instant.parse("2026-09-14T00:00:00Z"), jwt.issuedAt)
        assertEquals(Instant.parse("2026-09-14T01:00:00Z"), jwt.expiresAt)
        assertFalse(jwt.claims.containsKey("roles"))
    }
}
