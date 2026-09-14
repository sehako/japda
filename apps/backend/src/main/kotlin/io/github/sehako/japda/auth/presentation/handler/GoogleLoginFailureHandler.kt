package io.github.sehako.japda.auth.presentation.handler

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.authentication.AuthenticationFailureHandler

class GoogleLoginFailureHandler(private val failureUrl: String) : AuthenticationFailureHandler {
    override fun onAuthenticationFailure(request: HttpServletRequest, response: HttpServletResponse, exception: AuthenticationException) {
        response.sendRedirect("$failureUrl?error=GOOGLE_LOGIN_FAILED")
    }
}
