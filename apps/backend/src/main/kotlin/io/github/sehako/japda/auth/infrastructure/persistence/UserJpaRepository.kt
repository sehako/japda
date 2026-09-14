package io.github.sehako.japda.auth.infrastructure.persistence

import io.github.sehako.japda.auth.domain.model.IdentityProvider
import io.github.sehako.japda.auth.domain.model.User
import org.springframework.data.jpa.repository.JpaRepository

interface UserJpaRepository : JpaRepository<User, Long> {
    fun findByProviderAndProviderSubject(provider: IdentityProvider, providerSubject: String): User?
}
