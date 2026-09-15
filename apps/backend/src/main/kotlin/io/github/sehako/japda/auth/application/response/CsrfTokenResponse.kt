package io.github.sehako.japda.auth.application.response

data class CsrfTokenResponse(
    val token: String,
    val headerName: String,
)
