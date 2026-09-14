package io.github.sehako.japda.auth.application.response

data class CurrentUserResponse(
    val id: Long,
    val email: String,
    val roles: List<String>,
)
