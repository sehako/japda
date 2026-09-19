package io.github.sehako.japda.batch.settlement.domain.model

import java.math.BigInteger

data class IdPartitionRange(
	val startInclusive: Long,
	val endExclusive: Long,
	val endInclusive: Boolean,
)

data class IdPartitionPlan(
	val minId: Long?,
	val maxId: Long?,
	val ranges: List<IdPartitionRange>,
) {
	companion object {
		fun create(minId: Long?, maxId: Long?, partitionCount: Int): IdPartitionPlan {
			require(partitionCount > 0) { "파티션 수는 양수여야 합니다." }
			require((minId == null) == (maxId == null)) { "최소 ID와 최대 ID는 함께 존재해야 합니다." }
			if (minId == null || maxId == null) {
				return IdPartitionPlan(null, null, emptyList())
			}
			require(minId <= maxId) { "최소 ID는 최대 ID보다 클 수 없습니다." }

			val minimum = minId.toBigInteger()
			val maximum = maxId.toBigInteger()
			val idCount = maximum.subtract(minimum).add(BigInteger.ONE)
			val actualPartitionCount = idCount.min(partitionCount.toBigInteger()).intValueExact()
			val (baseSize, remainder) = idCount.divideAndRemainder(actualPartitionCount.toBigInteger())
			var start = minimum
			val ranges = List(actualPartitionCount) { index ->
				val size = baseSize.add(if (index.toBigInteger() < remainder) BigInteger.ONE else BigInteger.ZERO)
				val end = start.add(size)
				val range = if (end > LONG_MAX_VALUE) {
					IdPartitionRange(start.longValueExact(), Long.MAX_VALUE, true)
				} else {
					IdPartitionRange(start.longValueExact(), end.longValueExact(), false)
				}
				start = end
				range
			}
			return IdPartitionPlan(minId, maxId, ranges)
		}

		private val LONG_MAX_VALUE = Long.MAX_VALUE.toBigInteger()
	}
}
