package io.github.sehako.japda.auth.application.service

import io.github.sehako.japda.auth.domain.model.User
import io.github.sehako.japda.auth.domain.model.UserRole
import io.github.sehako.japda.auth.domain.repository.UserRepository
import io.github.sehako.japda.auth.domain.repository.UserRoleRepository
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GoogleLoginService(
    private val users: UserRepository,
    private val roles: UserRoleRepository,
    @Qualifier("adminEmails") private val adminEmails: Set<String>,
    private val clock: Clock,
) {
    @Transactional
    fun login(subject: String, email: String): Long {
        val user = users.findByGoogleSubject(subject)?.also { it.updateEmail(email) }
            ?: User.createGoogle(subject, email, clock.instant())
        val userId = checkNotNull(users.save(user).id)
        roles.addIfAbsent(userId, UserRole.BUYER)
        if (email.lowercase() in adminEmails.map(String::lowercase)) {
            roles.addIfAbsent(userId, UserRole.ADMIN)
        }
        return userId
    }
}
