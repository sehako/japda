package io.github.sehako.japda.payment.domain.model

enum class PaymentStatus {
	CONFIRMING,
	APPROVED,
	FAILED,
	REVIEW_REQUIRED,
}
