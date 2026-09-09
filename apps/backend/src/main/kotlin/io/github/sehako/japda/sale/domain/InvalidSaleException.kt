package io.github.sehako.japda.sale.domain

class InvalidSaleException(
	val errors: Map<String, String>,
) : IllegalArgumentException("판매 정보가 올바르지 않습니다.")
