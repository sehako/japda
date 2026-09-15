package io.github.sehako.japda.auth.exception

import io.github.sehako.japda.global.exception.ErrorCategory
import io.github.sehako.japda.global.exception.ErrorCode

enum class AuthErrorCode(
    override val code: String,
    override val message: String,
    override val category: ErrorCategory,
) : ErrorCode {
    UNAUTHENTICATED("AUTH_UNAUTHENTICATED", "인증이 필요합니다.", ErrorCategory.UNAUTHENTICATED),
    BUYER_LINK_REQUIRED("AUTH_BUYER_LINK_REQUIRED", "구매자 연결이 필요합니다.", ErrorCategory.FORBIDDEN),
    SELLER_LINK_REQUIRED("AUTH_SELLER_LINK_REQUIRED", "판매자 연결이 필요합니다.", ErrorCategory.FORBIDDEN),
    CSRF_INVALID("AUTH_CSRF_INVALID", "CSRF 토큰이 올바르지 않습니다.", ErrorCategory.FORBIDDEN),
    ;

    override val property: String? = null
}
