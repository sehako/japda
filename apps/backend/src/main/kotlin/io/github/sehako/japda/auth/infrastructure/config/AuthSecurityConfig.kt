package io.github.sehako.japda.auth.infrastructure.config

import io.github.sehako.japda.auth.application.service.GoogleLoginService
import io.github.sehako.japda.auth.domain.repository.UserRepository
import io.github.sehako.japda.auth.exception.AuthErrorCode
import io.github.sehako.japda.auth.infrastructure.token.JwtCookieAuthenticationConverter
import io.github.sehako.japda.auth.infrastructure.token.JwtCookieAuthenticationProvider
import io.github.sehako.japda.auth.infrastructure.token.ServiceJwtIssuer
import io.github.sehako.japda.auth.presentation.handler.GoogleLoginFailureHandler
import io.github.sehako.japda.auth.presentation.handler.GoogleLoginSuccessHandler
import io.github.sehako.japda.global.error.ProblemDetailFactory
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AuthenticationFilter
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.context.NullSecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler
import org.springframework.security.web.savedrequest.NullRequestCache
import org.springframework.security.web.util.matcher.RequestMatcher
import tools.jackson.databind.ObjectMapper
import java.time.Clock

@Profile("!perf")
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
        users: UserRepository,
    ): SecurityFilterChain {
        fun protectedRoute(request: HttpServletRequest): Boolean {
            val path = request.requestURI.removePrefix(request.contextPath)
            return when (request.method) {
                HttpMethod.GET.name() -> path in setOf(
                    "/api/auth/me",
                    "/api/auth/csrf",
                    "/api/products/ready",
                    "/api/checkout",
                    "/api/orders"
                )

                HttpMethod.POST.name() -> path in setOf(
                    "/api/products",
                    "/api/sales",
                    "/api/shipping-addresses",
                    "/api/orders",
                    "/api/payments/confirm"
                ) ||
                        Regex("^/api/products/[^/]+/images$").matches(path)

                else -> false
            }
        }

        fun unauthorized(request: HttpServletRequest, response: HttpServletResponse) {
            val problem = problemDetailFactory.create(AuthErrorCode.UNAUTHENTICATED, request.requestURI)
            response.status = problem.status
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
            response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
            objectMapper.writeValue(response.outputStream, problem)
        }

        val csrfRepository = CookieCsrfTokenRepository().apply {
            setHeaderName("X-CSRF-TOKEN")
            setCookieCustomizer { cookie ->
                cookie.path("/api").httpOnly(true).sameSite("Lax").secure(properties.cookieSecure)
            }
        }

        val jwtFilter = AuthenticationFilter(
            ProviderManager(
                JwtCookieAuthenticationProvider(
                    properties.signingKey(),
                    properties.issuer,
                    properties.audience,
                    users
                )
            ),
            JwtCookieAuthenticationConverter(),
        ).apply {
            setRequestMatcher { request -> protectedRoute(request) }
            setSecurityContextRepository(NullSecurityContextRepository())
            setFailureHandler { request, response, _ -> unauthorized(request, response) }
            setSuccessHandler(object : AuthenticationSuccessHandler {
                override fun onAuthenticationSuccess(
                    request: HttpServletRequest,
                    response: HttpServletResponse,
                    authentication: Authentication
                ) = Unit

                override fun onAuthenticationSuccess(
                    request: HttpServletRequest,
                    response: HttpServletResponse,
                    chain: FilterChain,
                    authentication: Authentication
                ) {
                    chain.doFilter(request, response)
                }
            })
        }

        http.authorizeHttpRequests { requests ->
            requests.requestMatchers(RequestMatcher { protectedRoute(it) }).access { authentication, _ ->
                org.springframework.security.authorization.AuthorizationDecision(authentication.get().principal is Long)
            }
            requests.anyRequest().permitAll()
        }
            .cors { }
            .csrf { csrf ->
                csrf.csrfTokenRepository(csrfRepository)
                    .csrfTokenRequestHandler(XorCsrfTokenRequestAttributeHandler())
                    .requireCsrfProtectionMatcher(RequestMatcher { request ->
                        request.method !in setOf("GET", "HEAD", "OPTIONS", "TRACE") && protectedRoute(request)
                    })
            }
            .securityContext { context -> context.securityContextRepository(NullSecurityContextRepository()) }
            .requestCache { cache -> cache.requestCache(NullRequestCache()) }
            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint { request, response, _ -> unauthorized(request, response) }
                    .accessDeniedHandler { request, response, _ ->
                        val error =
                            if (org.springframework.security.core.context.SecurityContextHolder.getContext().authentication?.principal is Long)
                                AuthErrorCode.CSRF_INVALID else AuthErrorCode.UNAUTHENTICATED
                        val problem = problemDetailFactory.create(error, request.requestURI)
                        response.status = problem.status
                        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
                        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
                        objectMapper.writeValue(response.outputStream, problem)
                    }
            }
            .addFilterBefore(jwtFilter, CsrfFilter::class.java)

        if (registrationRepository.getIfAvailable() != null) {
            properties.validateRedirects()
            val issuer = ServiceJwtIssuer(properties.signingKey(), properties.issuer, properties.audience, clock)
            http.oauth2Login { oauth ->
                oauth.successHandler(
                    GoogleLoginSuccessHandler(
                        loginService,
                        issuer,
                        properties.successUrl,
                        properties.failureUrl,
                        properties.cookieSecure,
                        csrfRepository
                    )
                )
                    .failureHandler(GoogleLoginFailureHandler(properties.failureUrl))
            }
        }
        return http.build()
    }
}
