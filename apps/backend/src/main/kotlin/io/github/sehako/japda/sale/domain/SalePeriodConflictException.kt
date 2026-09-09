package io.github.sehako.japda.sale.domain

class SalePeriodConflictException(
	cause: Throwable? = null,
) : RuntimeException("같은 상품의 판매 기간이 기존 판매 기간과 겹칩니다.", cause)
