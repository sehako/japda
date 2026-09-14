package io.github.sehako.japda.auth.infrastructure.token

import io.github.sehako.japda.auth.presentation.handler.GoogleLoginSuccessHandler
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.AuthenticationConverter

class JwtCookieAuthenticationConverter : AuthenticationConverter {
    override fun convert(request: HttpServletRequest): Authentication? {
        val token = request.cookies?.firstOrNull { it.name == GoogleLoginSuccessHandler.COOKIE_NAME }?.value
            ?.takeIf { it.isNotBlank() } ?: return null
        return UsernamePasswordAuthenticationToken.unauthenticated(null, token)
    }
}
