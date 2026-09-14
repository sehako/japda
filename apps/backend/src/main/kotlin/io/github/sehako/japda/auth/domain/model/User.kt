package io.github.sehako.japda.auth.domain.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "users")
class User private constructor(
    id: Long?,
    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 20)
    val provider: IdentityProvider,
    @field:Column(name = "provider_subject", nullable = false, length = 255)
    val providerSubject: String,
    email: String,
    @field:Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = id
        protected set

    @field:Column(nullable = false, length = 320)
    var email: String = email
        protected set

    fun updateEmail(email: String) {
        this.email = email
    }

    companion object {
        fun createGoogle(subject: String, email: String, createdAt: Instant): User =
            User(null, IdentityProvider.GOOGLE, subject, email, createdAt)
    }
}
