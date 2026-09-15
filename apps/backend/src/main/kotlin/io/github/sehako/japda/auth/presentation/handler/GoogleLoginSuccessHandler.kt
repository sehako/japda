package io.github.sehako.japda.auth.presentation.handler

import io.github.sehako.japda.auth.application.service.GoogleLoginService
import io.github.sehako.japda.auth.infrastructure.token.ServiceJwtIssuer
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseCookie
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.csrf.CsrfTokenRepository

class GoogleLoginSuccessHandler(
    private val loginService: GoogleLoginService,
    private val jwtIssuer: ServiceJwtIssuer,
    private val successUrl: String,
    private val failureUrl: String,
    private val secureCookie: Boolean,
    private val csrfTokens: CsrfTokenRepository,
) : AuthenticationSuccessHandler {
    override fun onAuthenticationSuccess(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication) {
        val user = authentication.principal as? OidcUser
        val subject = user?.subject?.takeIf { it.isNotBlank() }
        val email = user?.getClaimAsString("email")?.takeIf { it.isNotBlank() }
        if (user?.getClaimAsBoolean("email_verified") != true || email == null || subject == null) {
            response.sendRedirect("$failureUrl?error=EMAIL_UNVERIFIED")
            return
        }

        try {
            val userId = loginService.login(subject, email)
            csrfTokens.saveToken(null, request, response)
            val cookie = ResponseCookie.from(COOKIE_NAME, jwtIssuer.issue(userId))
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/api")
                .maxAge(ServiceJwtIssuer.TTL)
                .build()
            response.addHeader("Set-Cookie", cookie.toString())
            response.sendRedirect(successUrl)
        } catch (exception: Exception) {
            response.sendRedirect("$failureUrl?error=GOOGLE_LOGIN_FAILED")
        }
    }

    companion object {
        const val COOKIE_NAME = "JAPDA_ACCESS_TOKEN"
    }
}
