package io.github.sehako.japda.product.application.image

class InvalidProductImageUploadException(
	val errors: Map<String, String>,
) : IllegalArgumentException("상품 이미지 요청이 올바르지 않습니다.")

class ProductImagePayloadTooLargeException(
	val errors: Map<String, String>,
) : IllegalArgumentException("상품 이미지 용량 제한을 초과했습니다.")
