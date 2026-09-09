package io.github.sehako.japda.product.domain

class InvalidProductException(
	val errors: Map<String, String>,
) : IllegalArgumentException("상품 정보가 올바르지 않습니다.")
