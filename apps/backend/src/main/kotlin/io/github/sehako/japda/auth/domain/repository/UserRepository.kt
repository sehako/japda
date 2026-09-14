package io.github.sehako.japda.auth.domain.repository

import io.github.sehako.japda.auth.domain.model.User

interface UserRepository {
    fun findByGoogleSubject(subject: String): User?
    fun save(user: User): User
}
