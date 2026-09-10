package io.github.sehako.japda.global.exception

open class BusinessException(
	val errorCode: ErrorCode,
) : RuntimeException(errorCode.message)
