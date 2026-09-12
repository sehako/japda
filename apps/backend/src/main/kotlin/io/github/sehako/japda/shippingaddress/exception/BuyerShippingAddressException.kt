package io.github.sehako.japda.shippingaddress.exception

import io.github.sehako.japda.global.exception.BusinessException

class BuyerShippingAddressException(
	errorCode: BuyerShippingAddressErrorCode,
) : BusinessException(errorCode)
