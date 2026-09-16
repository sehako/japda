package io.github.sehako.japda.batch.settlement.infrastructure.batch.validation

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.batch.core.job.parameters.InvalidJobParametersException
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.job.parameters.JobParametersBuilder

@DisplayName("판매자 일일 정산 Job parameter 검증기")
class DailySellerSettlementJobParametersValidatorTest {
	private val validator = DailySellerSettlementJobParametersValidator(
		Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC),
	)

	@Test
	@DisplayName("정산일이 없으면 검증에 실패한다")
	fun 정산일이_없으면_검증에_실패한다() {
		val parameters = JobParametersBuilder()
			.addLong("platformFeeRateBps", 500L, false)
			.toJobParameters()

		assertFailsWith<InvalidJobParametersException> {
			validator.validate(parameters)
		}
	}

	@ParameterizedTest
	@ValueSource(strings = ["2026/09/15", "2026-9-15", "not-a-date"])
	@DisplayName("정산일 형식이 ISO 날짜가 아니면 검증에 실패한다")
	fun 정산일_형식이_ISO_날짜가_아니면_검증에_실패한다(settlementDate: String) {
		val parameters = validParameters(settlementDate = settlementDate)

		assertFailsWith<InvalidJobParametersException> {
			validator.validate(parameters)
		}
	}

	@ParameterizedTest
	@ValueSource(strings = ["2026-09-16", "2026-09-17"])
	@DisplayName("한국 기준 오늘 또는 미래 정산일이면 검증에 실패한다")
	fun 한국_기준_오늘_또는_미래_정산일이면_검증에_실패한다(settlementDate: String) {
		val parameters = validParameters(settlementDate = settlementDate)

		assertFailsWith<InvalidJobParametersException> {
			validator.validate(parameters)
		}
	}

	@Test
	@DisplayName("플랫폼 수수료율이 없으면 검증에 실패한다")
	fun 플랫폼_수수료율이_없으면_검증에_실패한다() {
		val parameters = JobParametersBuilder()
			.addString("settlementDate", "2026-09-15", true)
			.toJobParameters()

		assertFailsWith<InvalidJobParametersException> {
			validator.validate(parameters)
		}
	}

	@ParameterizedTest
	@ValueSource(longs = [-1L, 10001L])
	@DisplayName("플랫폼 수수료율이 허용 범위를 벗어나면 검증에 실패한다")
	fun 플랫폼_수수료율이_허용_범위를_벗어나면_검증에_실패한다(platformFeeRateBps: Long) {
		val parameters = validParameters(platformFeeRateBps = platformFeeRateBps)

		assertFailsWith<InvalidJobParametersException> {
			validator.validate(parameters)
		}
	}

	@Test
	@DisplayName("정산일만 식별 parameter이고 값이 유효하면 검증에 성공한다")
	fun 정산일만_식별_parameter이고_값이_유효하면_검증에_성공한다() {
		validator.validate(validParameters())
	}

	@Test
	@DisplayName("정산일 외의 식별 parameter가 있으면 검증에 실패한다")
	fun 정산일_외의_식별_parameter가_있으면_검증에_실패한다() {
		val parameters = JobParametersBuilder(validParameters())
			.addString("runId", "new-instance", true)
			.toJobParameters()

		assertFailsWith<InvalidJobParametersException> {
			validator.validate(parameters)
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = [true, false])
	@DisplayName("JobInstance 식별 계약이 다르면 검증에 실패한다")
	fun JobInstance_식별_계약이_다르면_검증에_실패한다(settlementDateIdentifying: Boolean) {
		val parameters = JobParametersBuilder()
			.addString("settlementDate", "2026-09-15", settlementDateIdentifying)
			.addLong("platformFeeRateBps", 500L, settlementDateIdentifying)
			.toJobParameters()

		assertFailsWith<InvalidJobParametersException> {
			validator.validate(parameters)
		}
	}

	private fun validParameters(
		settlementDate: String = "2026-09-15",
		platformFeeRateBps: Long = 500L,
	): JobParameters = JobParametersBuilder()
		.addString("settlementDate", settlementDate, true)
		.addLong("platformFeeRateBps", platformFeeRateBps, false)
		.toJobParameters()
}
