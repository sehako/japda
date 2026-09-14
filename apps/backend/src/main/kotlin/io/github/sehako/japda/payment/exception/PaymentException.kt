package io.github.sehako.japda.payment.exception

import io.github.sehako.japda.global.exception.BusinessException

class PaymentException(errorCode: PaymentErrorCode) : BusinessException(errorCode)
