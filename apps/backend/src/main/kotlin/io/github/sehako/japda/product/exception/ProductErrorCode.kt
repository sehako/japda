package io.github.sehako.japda.product.exception

import io.github.sehako.japda.global.exception.ErrorCategory
import io.github.sehako.japda.global.exception.ErrorCode

enum class ProductErrorCode(
	override val code: String,
	override val message: String,
	override val property: String,
) : ErrorCode {
	SELLER_ID_INVALID("PRODUCT_SELLER_ID_INVALID", "판매자 식별자는 양수여야 합니다.", "sellerId"),
	NAME_REQUIRED("PRODUCT_NAME_REQUIRED", "상품명은 필수입니다.", "name"),
	NAME_TOO_LONG("PRODUCT_NAME_TOO_LONG", "상품명은 100자 이하여야 합니다.", "name"),
	DESCRIPTION_TOO_LONG("PRODUCT_DESCRIPTION_TOO_LONG", "상품 설명은 3000자 이하여야 합니다.", "description"),
	;

	override val category: ErrorCategory = ErrorCategory.INVALID_REQUEST
}
