package io.github.sehako.japda.global.exception

interface ErrorCode {
	val code: String
	val category: ErrorCategory
	val message: String
	val property: String?
}
