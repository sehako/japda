package io.github.sehako.japda.auth.infrastructure.config

import io.github.sehako.japda.auth.application.service.GoogleLoginService
import io.github.sehako.japda.auth.infrastructure.token.ServiceJwtIssuer
import io.github.sehako.japda.auth.infrastructure.token.JwtCookieAuthenticationConverter
import io.github.sehako.japda.auth.infrastructure.token.JwtCookieAuthenticationProvider
import io.github.sehako.japda.auth.exception.AuthErrorCode
import io.github.sehako.japda.global.error.ProblemDetailFactory
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import io.github.sehako.japda.auth.presentation.handler.GoogleLoginFailureHandler
import io.github.sehako.japda.auth.presentation.handler.GoogleLoginSuccessHandler
import java.time.Clock
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.core.Authentication
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.authentication.AuthenticationFilter
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.context.NullSecurityContextRepository
import org.springframework.security.web.savedrequest.NullRequestCache
import tools.jackson.databind.ObjectMapper

@Configuration
@EnableConfigurationProperties(AuthProperties::class)
class AuthSecurityConfig {
    @Bean("adminEmails")
    fun adminEmails(properties: AuthProperties): Set<String> = properties.normalizedAdminEmails()

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    fun securityFilterChain(
        http: HttpSecurity,
        registrationRepository: ObjectProvider<ClientRegistrationRepository>,
        loginService: GoogleLoginService,
        properties: AuthProperties,
        clock: Clock,
        problemDetailFactory: ProblemDetailFactory,
        objectMapper: ObjectMapper,
    ): SecurityFilterChain {
        fun unauthorized(request: HttpServletRequest, response: HttpServletResponse) {
            val problem = problemDetailFactory.create(AuthErrorCode.UNAUTHENTICATED, request.requestURI)
            response.status = problem.status
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
            response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
            objectMapper.writeValue(response.outputStream, problem)
        }

        val jwtFilter = AuthenticationFilter(
            ProviderManager(JwtCookieAuthenticationProvider(properties.signingKey(), properties.issuer, properties.audience)),
            JwtCookieAuthenticationConverter(),
        ).apply {
            setRequestMatcher { request ->
                request.method == HttpMethod.GET.name() && request.requestURI.removePrefix(request.contextPath) == "/api/auth/me"
            }
            setSecurityContextRepository(NullSecurityContextRepository())
            setFailureHandler { request, response, _ -> unauthorized(request, response) }
            setSuccessHandler(object : AuthenticationSuccessHandler {
                override fun onAuthenticationSuccess(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication) = Unit

                override fun onAuthenticationSuccess(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain, authentication: Authentication) {
                    chain.doFilter(request, response)
                }
            })
        }

        http.authorizeHttpRequests { requests ->
            requests.requestMatchers(HttpMethod.GET, "/api/auth/me").access { authentication, _ ->
                org.springframework.security.authorization.AuthorizationDecision(authentication.get().principal is Long)
            }
            requests.anyRequest().permitAll()
        }
            .cors { }
            .csrf { csrf -> csrf.disable() }
            .securityContext { context -> context.securityContextRepository(NullSecurityContextRepository()) }
            .requestCache { cache -> cache.requestCache(NullRequestCache()) }
            .exceptionHandling { exceptions -> exceptions.authenticationEntryPoint { request, response, _ -> unauthorized(request, response) }
                .accessDeniedHandler { request, response, _ -> unauthorized(request, response) } }
            .addFilterBefore(jwtFilter, AuthorizationFilter::class.java)

        if (registrationRepository.getIfAvailable() != null) {
            properties.validateRedirects()
            val issuer = ServiceJwtIssuer(properties.signingKey(), properties.issuer, properties.audience, clock)
            http.oauth2Login { oauth ->
                oauth.successHandler(GoogleLoginSuccessHandler(loginService, issuer, properties.successUrl, properties.failureUrl, properties.cookieSecure))
                    .failureHandler(GoogleLoginFailureHandler(properties.failureUrl))
            }
        }
        return http.build()
    }
}
