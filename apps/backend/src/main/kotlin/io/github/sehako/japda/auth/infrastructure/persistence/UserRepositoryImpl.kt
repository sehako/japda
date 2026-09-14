package io.github.sehako.japda.auth.infrastructure.persistence

import io.github.sehako.japda.auth.domain.model.IdentityProvider
import io.github.sehako.japda.auth.domain.model.User
import io.github.sehako.japda.auth.domain.repository.UserRepository
import org.springframework.stereotype.Repository

@Repository
class UserRepositoryImpl(private val jpaRepository: UserJpaRepository) : UserRepository {
    override fun findByGoogleSubject(subject: String): User? =
        jpaRepository.findByProviderAndProviderSubject(IdentityProvider.GOOGLE, subject)

    override fun findById(id: Long): User? = jpaRepository.findById(id).orElse(null)

    override fun save(user: User): User = jpaRepository.save(user)
}
