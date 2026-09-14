package io.github.sehako.japda.auth.infrastructure.config

import java.net.URI
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("auth")
data class AuthProperties(
    val jwtSigningKey: String = "",
    val issuer: String = "http://localhost:8080",
    val audience: String = "japda-spa",
    val successUrl: String = "http://localhost:5173/auth/success",
    val failureUrl: String = "http://localhost:5173/auth/failure",
    val cookieSecure: Boolean = true,
    val adminEmails: List<String> = emptyList(),
) {
    fun normalizedAdminEmails(): Set<String> = adminEmails.map { email ->
        require(GMAIL_ADDRESS.matches(email)) { "관리자 이메일은 Gmail 주소여야 합니다." }
        email.lowercase()
    }.toSet()

    fun signingKey(): SecretKeySpec {
        val bytes = try {
            Base64.getDecoder().decode(jwtSigningKey)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("JWT 서명 키는 Base64 형식이어야 합니다.", exception)
        }
        require(bytes.size >= 32) { "JWT 서명 키는 256비트 이상이어야 합니다." }
        return SecretKeySpec(bytes, "HmacSHA256")
    }

    fun validateRedirects() {
        listOf(successUrl, failureUrl).forEach { url ->
            val uri = URI(url)
            require(uri.scheme in setOf("http", "https") && uri.host != null && uri.rawQuery == null && uri.rawFragment == null) {
                "로그인 리다이렉트 주소는 쿼리와 fragment가 없는 절대 HTTP URL이어야 합니다."
            }
        }
        require(URI(issuer).isAbsolute) { "JWT 발급자는 절대 URI여야 합니다." }
        require(audience.isNotBlank()) { "JWT 대상이 필요합니다." }
    }

    companion object {
        private val GMAIL_ADDRESS = Regex("[A-Za-z0-9._%+\\-]+@gmail\\.com", RegexOption.IGNORE_CASE)
    }
}
