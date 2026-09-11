package io.github.sehako.japda.order.exception

import io.github.sehako.japda.global.exception.BusinessException

class OrderException(
	errorCode: OrderErrorCode,
) : BusinessException(errorCode)
