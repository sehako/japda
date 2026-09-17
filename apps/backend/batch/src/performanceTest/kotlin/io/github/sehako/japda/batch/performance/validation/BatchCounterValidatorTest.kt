package io.github.sehako.japda.batch.performance.validation

import io.github.sehako.japda.batch.performance.measurement.model.ExecutionStatus
import io.github.sehako.japda.batch.performance.measurement.model.StepMeasurement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Spring Batch counter 검산기")
class BatchCounterValidatorTest {
	@Test
	fun chunk_step의_입출력과_commit_count가_계약과_일치하면_성공한다() {
		val report = BatchCounterValidator(chunkSize = 100).validate(
			steps = listOf(
				tasklet("prepareSettlementRunStep"),
				step("collectSettlementDetailsStep", read = 201, write = 201, commit = 3),
				tasklet("completeSettlementCollectionStep"),
				tasklet("confirmSellerSettlementsStep"),
				step("creditSellerWalletsStep", read = 2, write = 2, commit = 1),
				tasklet("completeSettlementRunStep"),
			),
			expectedOrderCount = 201,
			expectedSellerCount = 2,
		)

		assertThat(report.success).isTrue()
		assertThat(report.failures).isEmpty()
	}

	@Test
	fun rollback이나_skip이_있으면_실패한다() {
		val report = BatchCounterValidator(100).validate(
			steps = listOf(
				tasklet("prepareSettlementRunStep"),
				step("collectSettlementDetailsStep", read = 2, write = 1, commit = 1, skip = 1, rollback = 1),
				tasklet("completeSettlementCollectionStep"),
				tasklet("confirmSellerSettlementsStep"),
				step("creditSellerWalletsStep", read = 1, write = 1, commit = 1),
				tasklet("completeSettlementRunStep"),
			),
			expectedOrderCount = 2,
			expectedSellerCount = 1,
		)

		assertThat(report.success).isFalse()
		assertThat(report.failures).anyMatch { it.contains("skip") }
		assertThat(report.failures).anyMatch { it.contains("rollback") }
	}

	@Test
	fun 기술_step이_하나라도_누락되면_실패한다() {
		val report = BatchCounterValidator(100).validate(
			steps = listOf(
				tasklet("prepareSettlementRunStep"),
				step("collectSettlementDetailsStep", read = 1, write = 1, commit = 1),
				tasklet("completeSettlementCollectionStep"),
				tasklet("confirmSellerSettlementsStep"),
				step("creditSellerWalletsStep", read = 1, write = 1, commit = 1),
			),
			expectedOrderCount = 1,
			expectedSellerCount = 1,
		)

		assertThat(report.success).isFalse()
		assertThat(report.failures).contains("필수 Step 실행 결과가 없습니다: completeSettlementRunStep")
	}

	private fun step(
		name: String,
		read: Long,
		write: Long,
		commit: Long,
		skip: Long = 0,
		rollback: Long = 0,
	) = StepMeasurement(name, ExecutionStatus.COMPLETED, 10, read, write, 0, skip, commit, rollback)

	private fun tasklet(name: String) = StepMeasurement(name, ExecutionStatus.COMPLETED, 10, 0, 0, 0, 0, 1, 0)
}
