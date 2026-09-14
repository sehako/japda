package io.github.sehako.japda.auth.infrastructure.config

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName

@DisplayName("Google 로그인 설정")
class AuthPropertiesTest {
    @Test
    @DisplayName("관리자 주소는 Gmail만 허용하고 대소문자를 통일한다")
    fun 관리자_주소_Gmail_검증과_정규화() {
        val settings = AuthProperties(adminEmails = listOf("ADMIN@GMAIL.COM"))

        assertEquals(setOf("admin@gmail.com"), settings.normalizedAdminEmails())
        assertFailsWith<IllegalArgumentException> {
            AuthProperties(adminEmails = listOf("admin@example.com")).normalizedAdminEmails()
        }
    }

    @Test
    @DisplayName("짧거나 비어 있는 JWT 서명 키를 거부한다")
    fun JWT_서명_키_길이_검증() {
        assertFailsWith<IllegalArgumentException> { AuthProperties(jwtSigningKey = "").signingKey() }
        assertFailsWith<IllegalArgumentException> {
            AuthProperties(jwtSigningKey = Base64.getEncoder().encodeToString(ByteArray(16))).signingKey()
        }
    }
}
