package io.github.sehako.japda.batch.settlement.infrastructure.batch.partition

import io.github.sehako.japda.batch.settlement.domain.model.IdPartitionPlan
import io.github.sehako.japda.batch.settlement.domain.model.IdPartitionRange
import org.springframework.batch.infrastructure.item.ExecutionContext

enum class PartitionPlanType(
	val keyPrefix: String,
) {
	COLLECTION("collection"),
	CREDIT("credit"),
}

class IdPartitionPlanService {
	fun getOrCreate(
		executionContext: ExecutionContext,
		type: PartitionPlanType,
		minId: Long?,
		maxId: Long?,
		partitionCount: Int,
	): IdPartitionPlan {
		val prefix = "${type.keyPrefix}.partition"
		val hasStoredValues = executionContext.entrySet().any { (key) -> key.startsWith("$prefix.") }
		if (executionContext.containsKey("$prefix.planned")) {
			return restore(executionContext, prefix)
		}
		check(!hasStoredValues) { "파티션 계획의 일부 값만 저장되어 있습니다: type=${type.keyPrefix}" }

		return IdPartitionPlan.create(minId, maxId, partitionCount).also { plan ->
			store(executionContext, prefix, plan)
		}
	}

	private fun store(executionContext: ExecutionContext, prefix: String, plan: IdPartitionPlan) {
		executionContext.put("$prefix.planned", true)
		executionContext.putInt("$prefix.count", plan.ranges.size)
		plan.minId?.let { executionContext.putLong("$prefix.minId", it) }
		plan.maxId?.let { executionContext.putLong("$prefix.maxId", it) }
		plan.ranges.forEachIndexed { index, range ->
			val rangePrefix = rangePrefix(prefix, index)
			executionContext.putLong("$rangePrefix.startInclusive", range.startInclusive)
			executionContext.putLong("$rangePrefix.endExclusive", range.endExclusive)
			executionContext.put("$rangePrefix.endInclusive", range.endInclusive)
		}
	}

	private fun restore(executionContext: ExecutionContext, prefix: String): IdPartitionPlan = try {
		check(executionContext.get("$prefix.planned") == true) { "파티션 계획 완료 표식이 올바르지 않습니다." }
		val count = executionContext.getInt("$prefix.count")
		check(count >= 0) { "저장된 파티션 수가 음수입니다." }
		if (count == 0) {
			check(!executionContext.containsKey("$prefix.minId") && !executionContext.containsKey("$prefix.maxId")) {
				"빈 파티션 계획에 ID 경계가 저장되어 있습니다."
			}
			checkStoredKeys(executionContext, prefix, count)
			return IdPartitionPlan(null, null, emptyList())
		}

		val minId = executionContext.getLong("$prefix.minId")
		val maxId = executionContext.getLong("$prefix.maxId")
		val ranges = List(count) { index ->
			val rangePrefix = rangePrefix(prefix, index)
			IdPartitionRange(
				startInclusive = executionContext.getLong("$rangePrefix.startInclusive"),
				endExclusive = executionContext.getLong("$rangePrefix.endExclusive"),
				endInclusive = executionContext.get("$rangePrefix.endInclusive") as Boolean,
			)
		}
		validate(IdPartitionPlan(minId, maxId, ranges))
		checkStoredKeys(executionContext, prefix, count)
		IdPartitionPlan(minId, maxId, ranges)
	} catch (exception: IllegalStateException) {
		throw exception
	} catch (exception: RuntimeException) {
		throw IllegalStateException("저장된 파티션 계획을 복원할 수 없습니다: prefix=$prefix", exception)
	}

	private fun validate(plan: IdPartitionPlan) {
		val minId = checkNotNull(plan.minId) { "파티션 계획의 최소 ID가 없습니다." }
		val maxId = checkNotNull(plan.maxId) { "파티션 계획의 최대 ID가 없습니다." }
		check(minId <= maxId) { "파티션 계획의 최소 ID가 최대 ID보다 큽니다." }
		check(plan.ranges.isNotEmpty()) { "ID 경계가 있는 파티션 계획은 범위가 비어 있을 수 없습니다." }
		check(plan.ranges.first().startInclusive == minId) { "첫 파티션이 최소 ID에서 시작하지 않습니다." }

		plan.ranges.forEachIndexed { index, range ->
			if (range.endInclusive) {
				check(index == plan.ranges.lastIndex && range.endExclusive == Long.MAX_VALUE) {
					"상한 포함 범위는 Long 최댓값에서 끝나는 마지막 파티션이어야 합니다."
				}
				check(range.startInclusive <= range.endExclusive) { "파티션 범위의 시작과 끝이 역전되었습니다." }
			} else {
				check(range.startInclusive < range.endExclusive) { "파티션 범위의 시작과 끝이 역전되었거나 비어 있습니다." }
			}
			if (index > 0) {
				val previous = plan.ranges[index - 1]
				check(!previous.endInclusive && previous.endExclusive == range.startInclusive) {
					"파티션 범위가 누락되거나 중첩되었습니다."
				}
			}
		}

		val last = plan.ranges.last()
		if (maxId == Long.MAX_VALUE) {
			check(last.endInclusive && last.endExclusive == maxId) { "마지막 파티션이 최대 ID를 포함하지 않습니다." }
		} else {
			check(!last.endInclusive && last.endExclusive == maxId + 1) { "마지막 파티션이 최대 ID 다음에서 끝나지 않습니다." }
		}
	}

	private fun checkStoredKeys(executionContext: ExecutionContext, prefix: String, count: Int) {
		val expectedKeys = buildSet {
			add("$prefix.planned")
			add("$prefix.count")
			if (count > 0) {
				add("$prefix.minId")
				add("$prefix.maxId")
			}
			repeat(count) { index ->
				val rangePrefix = rangePrefix(prefix, index)
				add("$rangePrefix.startInclusive")
				add("$rangePrefix.endExclusive")
				add("$rangePrefix.endInclusive")
			}
		}
		val storedKeys = executionContext.entrySet()
			.map { (key) -> key }
			.filterTo(mutableSetOf()) { key -> key.startsWith("$prefix.") }
		check(storedKeys == expectedKeys) { "저장된 파티션 계획 key가 실제 파티션 수와 일치하지 않습니다." }
	}

	private fun rangePrefix(prefix: String, index: Int) = "$prefix.${index.toString().padStart(3, '0')}"
}
