package io.github.sehako.japda.batch.performance.validation

import io.github.sehako.japda.batch.performance.measurement.model.ExecutionStatus
import io.github.sehako.japda.batch.performance.measurement.model.StepMeasurement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Spring Batch counter 검산기")
class BatchCounterValidatorTest {
	@Test
	fun partition_worker별_counter를_합산한_값이_계약과_일치하면_성공한다() {
		val report = BatchCounterValidator(chunkSize = 100).validate(
			steps = listOf(
				tasklet("prepareSettlementRunStep"),
				tasklet("prepareCollectionPartitionPlanStep"),
				tasklet("collectSettlementDetailsManagerStep"),
				step("collectSettlementDetailsWorkerStep:collectionPartition000", read = 100, write = 100, commit = 1),
				step("collectSettlementDetailsWorkerStep:collectionPartition001", read = 101, write = 101, commit = 2),
				tasklet("completeSettlementCollectionStep"),
				tasklet("confirmSellerSettlementsStep"),
				tasklet("prepareCreditPartitionPlanStep"),
				tasklet("creditSellerWalletsManagerStep"),
				step("creditSellerWalletsWorkerStep:creditPartition000", read = 1, write = 1, commit = 1),
				step("creditSellerWalletsWorkerStep:creditPartition001", read = 1, write = 1, commit = 1),
				tasklet("completeSettlementRunStep"),
			),
			expectedOrderCount = 201,
			expectedSellerCount = 2,
		)

		assertThat(report.success).isTrue()
		assertThat(report.failures).isEmpty()
		assertThat(report.values).containsEntry("batch.collection.worker.read.count", "201")
		assertThat(report.values).containsEntry("batch.collection.worker.write.count", "201")
		assertThat(report.values).containsEntry("batch.collection.worker.commit.count", "3")
		assertThat(report.values).containsEntry("batch.credit.worker.read.count", "2")
		assertThat(report.values).containsEntry("batch.credit.worker.write.count", "2")
		assertThat(report.values).containsEntry("batch.credit.worker.commit.count", "2")
	}

	@Test
	fun rollback이나_skip이_있으면_실패한다() {
		val report = BatchCounterValidator(100).validate(
			steps = listOf(
				tasklet("prepareSettlementRunStep"),
				tasklet("prepareCollectionPartitionPlanStep"),
				tasklet("collectSettlementDetailsManagerStep"),
				step("collectSettlementDetailsWorkerStep:collectionPartition000", read = 2, write = 1, commit = 1, skip = 1, rollback = 1),
				tasklet("completeSettlementCollectionStep"),
				tasklet("confirmSellerSettlementsStep"),
				tasklet("prepareCreditPartitionPlanStep"),
				tasklet("creditSellerWalletsManagerStep"),
				step("creditSellerWalletsWorkerStep:creditPartition000", read = 1, write = 1, commit = 1),
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
				tasklet("prepareCollectionPartitionPlanStep"),
				tasklet("collectSettlementDetailsManagerStep"),
				step("collectSettlementDetailsWorkerStep:collectionPartition000", read = 1, write = 1, commit = 1),
				tasklet("completeSettlementCollectionStep"),
				tasklet("confirmSellerSettlementsStep"),
				tasklet("prepareCreditPartitionPlanStep"),
				tasklet("creditSellerWalletsManagerStep"),
				step("creditSellerWalletsWorkerStep:creditPartition000", read = 1, write = 1, commit = 1),
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
