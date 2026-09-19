package io.github.sehako.japda.auth.infrastructure.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.web.filter.OncePerRequestFilter

@Profile("perf")
@Configuration
class PerfSecurityConfig {

    @Bean
    fun perfSecurityFilterChain(
        http: HttpSecurity,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { }
            .authorizeHttpRequests {
                it.anyRequest().permitAll()
            }
            .addFilterBefore(
                PerfAuthenticationFilter(),
                AuthorizationFilter::class.java,
            )

        return http.build()
    }

    private class PerfAuthenticationFilter : OncePerRequestFilter() {

        override fun doFilterInternal(
            request: HttpServletRequest,
            response: HttpServletResponse,
            filterChain: FilterChain,
        ) {
            val authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                    TEST_USER_ID,
                    null,
                    emptyList(),
                )

            SecurityContextHolder.getContext().authentication =
                authentication

            filterChain.doFilter(request, response)
        }
    }

    private companion object {
        const val TEST_USER_ID = 900001L
    }
}