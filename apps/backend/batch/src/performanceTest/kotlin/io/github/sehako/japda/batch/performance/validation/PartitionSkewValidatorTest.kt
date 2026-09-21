package io.github.sehako.japda.batch.performance.validation

import io.github.sehako.japda.batch.performance.measurement.model.ExecutionStatus
import io.github.sehako.japda.batch.performance.measurement.model.StepMeasurement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("파티션 처리 편향 검산기")
class PartitionSkewValidatorTest {
	@Test
	@DisplayName("100건 smoke 실행은 파티션 편향을 기록하되 실패로 판정하지 않는다")
	fun 백건_smoke_파티션_편향_기록만_수행() {
		val report = PartitionSkewValidator().validate(
			listOf(
				step("collectSettlementDetailsManagerStep", duration = 50),
				step("collectSettlementDetailsWorkerStep:collectionPartition000", duration = 25, read = 25, write = 25, commit = 3),
				step("creditSellerWalletsManagerStep", duration = 50),
				step("creditSellerWalletsWorkerStep:creditPartition000", duration = 25, read = 3, write = 3, commit = 1),
			),
			100,
		)

		assertThat(report.success).isTrue()
		assertThat(report.values).containsEntry("partition.collection.longest.ratio", "0.500")
	}

	@Test
	fun 최장_worker_시간이_phase의_25퍼센트를_초과하면_실패한다() {
		val report = PartitionSkewValidator().validate(
			listOf(
				step("collectSettlementDetailsManagerStep", duration = 1_000),
				step("collectSettlementDetailsWorkerStep:collectionPartition000", duration = 251, read = 10, write = 10, commit = 1),
				step("collectSettlementDetailsWorkerStep:collectionPartition001", duration = 249, read = 20, write = 20, commit = 2),
				step("creditSellerWalletsManagerStep", duration = 2_000),
				step("creditSellerWalletsWorkerStep:creditPartition000", duration = 500, read = 3, write = 3, commit = 1),
			),
		)

		assertThat(report.success).isFalse()
		assertThat(report.values).containsEntry("partition.collection.longest.ratio", "0.251")
		assertThat(report.values).containsEntry("partition.credit.longest.ratio", "0.250")
		assertThat(report.failures).containsExactly(
			"collection 파티션 처리 편향이 25.0%를 초과했습니다: partition=collectionPartition000, ratio=25.1%",
		)
	}

	private fun step(
		name: String,
		duration: Long,
		read: Long = 0,
		write: Long = 0,
		commit: Long = 0,
	) = StepMeasurement(name, ExecutionStatus.COMPLETED, duration, read, write, 0, 0, commit, 0)
}
