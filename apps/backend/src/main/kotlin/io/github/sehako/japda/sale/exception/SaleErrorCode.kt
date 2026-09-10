package io.github.sehako.japda.sale.exception

import io.github.sehako.japda.global.exception.ErrorCategory
import io.github.sehako.japda.global.exception.ErrorCode

enum class SaleErrorCode(
	override val code: String,
	override val message: String,
	override val property: String?,
	override val category: ErrorCategory = ErrorCategory.INVALID_REQUEST,
) : ErrorCode {
	SELLER_ID_INVALID("SALE_SELLER_ID_INVALID", "판매자 식별자는 양수여야 합니다.", "sellerId"),
	PRODUCT_ID_INVALID("SALE_PRODUCT_ID_INVALID", "상품 식별자는 양수여야 합니다.", "productId"),
	DATE_REQUIRED("SALE_DATE_REQUIRED", "판매일은 필수입니다.", "saleDate"),
	PRICE_INVALID("SALE_PRICE_INVALID", "가격은 양수여야 합니다.", "price"),
	QUANTITY_INVALID("SALE_QUANTITY_INVALID", "판매 수량은 양수여야 합니다.", "quantity"),
	PRODUCT_NOT_FOUND("SALE_PRODUCT_NOT_FOUND", "판매할 상품을 찾을 수 없습니다.", "productId", ErrorCategory.NOT_FOUND),
	PRODUCT_NOT_READY("SALE_PRODUCT_NOT_READY", "판매 준비가 완료된 상품만 등록할 수 있습니다.", "productId", ErrorCategory.CONFLICT),
	REGISTRATION_CLOSED("SALE_REGISTRATION_CLOSED", "판매 일정 등록 가능 시간이 아닙니다.", "saleDate", ErrorCategory.CONFLICT),
	SELLER_ALREADY_REGISTERED("SALE_SELLER_ALREADY_REGISTERED", "같은 판매일에 이미 판매 일정을 등록했습니다.", "sellerId", ErrorCategory.CONFLICT),
	CAPACITY_EXCEEDED("SALE_CAPACITY_EXCEEDED", "판매일별 등록 정원을 초과했습니다.", "saleDate", ErrorCategory.CONFLICT),
	DATE_OUT_OF_RANGE("SALE_DATE_OUT_OF_RANGE", "판매일은 내일까지 조회할 수 있습니다.", "saleDate"),
	;
}
