package io.github.sehako.japda.auth.infrastructure.config

import io.github.sehako.japda.auth.application.service.GoogleLoginService
import io.github.sehako.japda.auth.infrastructure.token.ServiceJwtIssuer
import io.github.sehako.japda.auth.presentation.handler.GoogleLoginFailureHandler
import io.github.sehako.japda.auth.presentation.handler.GoogleLoginSuccessHandler
import java.time.Clock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.NullSecurityContextRepository

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
    ): SecurityFilterChain {
        http.authorizeHttpRequests { requests -> requests.anyRequest().permitAll() }
            .csrf { csrf -> csrf.disable() }
            .securityContext { context -> context.securityContextRepository(NullSecurityContextRepository()) }

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
