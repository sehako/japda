package io.github.sehako.japda.batch.settlement.infrastructure.batch.validation

import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import org.springframework.batch.core.job.parameters.InvalidJobParametersException
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.job.parameters.JobParametersValidator

class DailySellerSettlementJobParametersValidator(
	private val clock: Clock,
) : JobParametersValidator {
	override fun validate(parameters: JobParameters) {
		val settlementDateParameter = parameters.getParameter(SETTLEMENT_DATE_PARAMETER)
			?: invalid("settlementDate가 필요합니다.")
		val settlementDateValue = settlementDateParameter.value() as? String
			?: invalid("settlementDate는 ISO-8601 문자열이어야 합니다.")
		val settlementDate = try {
			LocalDate.parse(settlementDateValue)
		} catch (_: DateTimeException) {
			invalid("settlementDate 형식이 올바르지 않습니다.")
		}
		if (!settlementDate.isBefore(LocalDate.now(clock.withZone(SEOUL_ZONE)))) {
			invalid("settlementDate는 한국 시간 기준 과거 날짜여야 합니다.")
		}
		if (!settlementDateParameter.identifying()) {
			invalid("settlementDate는 식별 parameter여야 합니다.")
		}
		val identifyingParameterNames = parameters.identifyingParameters.map { it.name() }.toSet()
		if (identifyingParameterNames != setOf(SETTLEMENT_DATE_PARAMETER)) {
			invalid("settlementDate 외의 식별 parameter는 허용하지 않습니다.")
		}

		val platformFeeRateParameter = parameters.getParameter(PLATFORM_FEE_RATE_BPS_PARAMETER)
			?: invalid("platformFeeRateBps가 필요합니다.")
		val platformFeeRateBps = platformFeeRateParameter.value() as? Long
			?: invalid("platformFeeRateBps는 정수여야 합니다.")
		if (platformFeeRateBps !in MIN_PLATFORM_FEE_RATE_BPS..MAX_PLATFORM_FEE_RATE_BPS) {
			invalid("platformFeeRateBps가 허용 범위를 벗어났습니다.")
		}
		if (platformFeeRateParameter.identifying()) {
			invalid("platformFeeRateBps는 비식별 parameter여야 합니다.")
		}
	}

	private fun invalid(message: String): Nothing = throw InvalidJobParametersException(message)

	companion object {
		const val SETTLEMENT_DATE_PARAMETER = "settlementDate"
		const val PLATFORM_FEE_RATE_BPS_PARAMETER = "platformFeeRateBps"

		private val SEOUL_ZONE = ZoneId.of("Asia/Seoul")
		private const val MIN_PLATFORM_FEE_RATE_BPS = 0L
		private const val MAX_PLATFORM_FEE_RATE_BPS = 10_000L
	}
}
