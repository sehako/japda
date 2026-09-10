package io.github.sehako.japda.sale.exception

import io.github.sehako.japda.global.exception.BusinessException

class SaleException(
	errorCode: SaleErrorCode,
) : BusinessException(errorCode)
