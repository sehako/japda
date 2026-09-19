package io.github.sehako.japda.batch.settlement.infrastructure.batch.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("japda.batch.daily-seller-settlement")
data class DailySellerSettlementBatchProperties(
	val chunkSize: Int = 100,
	val pageSize: Int = 100,
	val fetchSize: Int = 100,
	val workerCount: Int = 8,
	val collectionPartitionCount: Int = 64,
	val creditPartitionCount: Int = 64,
) {
	init {
		require(chunkSize > 0) { "판매자 일일 정산 chunk 크기는 양수여야 합니다." }
		require(pageSize > 0) { "판매자 일일 정산 page 크기는 양수여야 합니다." }
		require(fetchSize > 0) { "판매자 일일 정산 fetch 크기는 양수여야 합니다." }
		require(workerCount > 0) { "판매자 일일 정산 worker 수는 양수여야 합니다." }
		require(collectionPartitionCount > 0) { "판매자 일일 정산 수집 파티션 수는 양수여야 합니다." }
		require(creditPartitionCount > 0) { "판매자 일일 정산 입금 파티션 수는 양수여야 합니다." }
		require(collectionPartitionCount >= workerCount) { "판매자 일일 정산 수집 파티션 수는 worker 수 이상이어야 합니다." }
		require(creditPartitionCount >= workerCount) { "판매자 일일 정산 입금 파티션 수는 worker 수 이상이어야 합니다." }
	}
}
