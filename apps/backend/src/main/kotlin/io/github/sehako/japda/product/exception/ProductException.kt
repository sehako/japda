package io.github.sehako.japda.product.exception

import io.github.sehako.japda.global.exception.BusinessException

class ProductException(
	errorCode: ProductErrorCode,
) : BusinessException(errorCode)
