package io.github.sehako.japda.product.exception

import io.github.sehako.japda.global.exception.ErrorCategory
import io.github.sehako.japda.global.exception.ErrorCode

enum class ProductErrorCode(
	override val code: String,
	override val message: String,
	override val property: String,
	override val category: ErrorCategory = ErrorCategory.INVALID_REQUEST,
) : ErrorCode {
	SELLER_ID_INVALID("PRODUCT_SELLER_ID_INVALID", "판매자 식별자는 양수여야 합니다.", "sellerId"),
	ID_INVALID("PRODUCT_ID_INVALID", "상품 식별자는 양수여야 합니다.", "productId"),
	NAME_REQUIRED("PRODUCT_NAME_REQUIRED", "상품명은 필수입니다.", "name"),
	NAME_TOO_LONG("PRODUCT_NAME_TOO_LONG", "상품명은 100자 이하여야 합니다.", "name"),
	DESCRIPTION_TOO_LONG("PRODUCT_DESCRIPTION_TOO_LONG", "상품 설명은 3000자 이하여야 합니다.", "description"),
	IMAGE_COUNT_INVALID("PRODUCT_IMAGE_COUNT_INVALID", "상품 이미지는 1장 이상 10장 이하여야 합니다.", "files"),
	IMAGE_REPRESENTATIVE_INVALID("PRODUCT_IMAGE_REPRESENTATIVE_INVALID", "대표 이미지는 정확히 1장이어야 합니다.", "representativeIndex"),
	IMAGE_FILE_INVALID("PRODUCT_IMAGE_FILE_INVALID", "빈 이미지 파일은 등록할 수 없습니다.", "files"),
	IMAGE_FORMAT_UNSUPPORTED("PRODUCT_IMAGE_FORMAT_UNSUPPORTED", "지원하지 않는 이미지 형식입니다.", "files"),
	IMAGE_SIZE_EXCEEDED("PRODUCT_IMAGE_SIZE_EXCEEDED", "상품 이미지 크기 제한을 초과했습니다.", "files", ErrorCategory.PAYLOAD_TOO_LARGE),
	NOT_FOUND("PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다.", "productId", ErrorCategory.NOT_FOUND),
	ACCESS_DENIED("PRODUCT_ACCESS_DENIED", "상품에 접근할 권한이 없습니다.", "sellerId", ErrorCategory.FORBIDDEN),
	IMAGES_ALREADY_REGISTERED("PRODUCT_IMAGES_ALREADY_REGISTERED", "상품 이미지가 이미 등록되었습니다.", "productId", ErrorCategory.CONFLICT),
	IMAGE_STORAGE_FAILED("PRODUCT_IMAGE_STORAGE_FAILED", "상품 이미지 저장에 실패했습니다.", "files", ErrorCategory.INTERNAL_SERVER_ERROR),
	;
}
