package io.github.sehako.japda.auth.infrastructure.token

import io.github.sehako.japda.auth.domain.repository.UserRepository
import java.time.Duration
import javax.crypto.SecretKey
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt

class JwtCookieAuthenticationProvider(key: SecretKey, issuer: String, audience: String, private val users: UserRepository) : AuthenticationProvider {
    private val decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build().apply {
        setJwtValidator(DelegatingOAuth2TokenValidator(
            JwtIssuerValidator(issuer),
            JwtTimestampValidator(Duration.ZERO),
            OAuth2TokenValidator<Jwt> { jwt ->
                if (jwt.expiresAt != null && jwt.audience?.contains(audience) == true) OAuth2TokenValidatorResult.success()
                else OAuth2TokenValidatorResult.failure(org.springframework.security.oauth2.core.OAuth2Error("invalid_token"))
            },
        ))
    }

    override fun authenticate(authentication: Authentication): Authentication {
        val token = authentication.credentials as? String ?: throw BadCredentialsException("인증에 실패했습니다.")
        val userId = try {
            decoder.decode(token).subject?.toLongOrNull()?.takeIf { it > 0 }
        } catch (exception: JwtException) {
            null
        }
        if (userId == null || users.findById(userId) == null) throw BadCredentialsException("인증에 실패했습니다.")
        return UsernamePasswordAuthenticationToken.authenticated(userId, null, emptyList())
    }

    override fun supports(authentication: Class<*>): Boolean = UsernamePasswordAuthenticationToken::class.java.isAssignableFrom(authentication)
}
