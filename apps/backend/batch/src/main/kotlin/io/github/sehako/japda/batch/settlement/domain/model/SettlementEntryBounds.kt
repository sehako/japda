package io.github.sehako.japda.batch.settlement.domain.model

import org.springframework.batch.infrastructure.item.ExecutionContext

data class SettlementEntryBounds(
	val minId: Long?,
	val maxId: Long?,
) {
	init {
		require((minId == null) == (maxId == null)) { "정산 원천 ID 경계는 함께 존재해야 합니다." }
		require(minId == null || minId <= checkNotNull(maxId)) { "정산 원천 ID 경계가 올바르지 않습니다." }
	}

	companion object {
		private const val MIN_ID_KEY = "collection.partition.minId"
		private const val MAX_ID_KEY = "collection.partition.maxId"

		fun from(executionContext: ExecutionContext): SettlementEntryBounds {
			val hasMinId = executionContext.containsKey(MIN_ID_KEY)
			val hasMaxId = executionContext.containsKey(MAX_ID_KEY)
			if (hasMinId != hasMaxId) {
				throw IllegalStateException("저장된 정산 원천 ID 경계가 일부만 존재합니다.")
			}
			if (!hasMinId) return SettlementEntryBounds(null, null)
			return SettlementEntryBounds(
				executionContext.getLong(MIN_ID_KEY),
				executionContext.getLong(MAX_ID_KEY),
			)
		}
	}
}
