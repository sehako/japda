package io.github.sehako.japda.auth.exception

import io.github.sehako.japda.global.exception.ErrorCategory
import io.github.sehako.japda.global.exception.ErrorCode

enum class AuthErrorCode(
    override val code: String,
    override val message: String,
    override val category: ErrorCategory,
) : ErrorCode {
    UNAUTHENTICATED("AUTH_UNAUTHENTICATED", "인증이 필요합니다.", ErrorCategory.UNAUTHENTICATED),
    ;

    override val property: String? = null
}
