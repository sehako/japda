package io.github.sehako.japda.batch.settlement.infrastructure.batch.config

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

@DisplayName("판매자 일일 정산 배치 설정")
class DailySellerSettlementBatchPropertiesTest {
	private val contextRunner = ApplicationContextRunner()
		.withUserConfiguration(PropertiesConfiguration::class.java)

	@Test
	@DisplayName("설정이 없으면 기존 처리 단위인 100을 사용한다")
	fun 설정_없음_기존_처리_단위_100을_사용한다() {
		contextRunner.run { context ->
			val properties = context.getBean(DailySellerSettlementBatchProperties::class.java)

			assertEquals(100, properties.chunkSize)
			assertEquals(100, properties.pageSize)
			assertEquals(100, properties.fetchSize)
			assertEquals(8, properties.workerCount)
			assertEquals(64, properties.collectionPartitionCount)
			assertEquals(64, properties.creditPartitionCount)
		}
	}

	@Test
	@DisplayName("외부 설정을 worker와 collection 및 credit 파티션 수에 바인딩한다")
	fun 외부_설정_worker와_collection_credit_파티션_수에_바인딩한다() {
		contextRunner
			.withPropertyValues(
				"japda.batch.daily-seller-settlement.worker-count=3",
				"japda.batch.daily-seller-settlement.collection-partition-count=7",
				"japda.batch.daily-seller-settlement.credit-partition-count=9",
			)
			.run { context ->
				val properties = context.getBean(DailySellerSettlementBatchProperties::class.java)

				assertEquals(3, properties.workerCount)
				assertEquals(7, properties.collectionPartitionCount)
				assertEquals(9, properties.creditPartitionCount)
			}
	}

	@Test
	@DisplayName("worker와 파티션 수가 0 이하이면 애플리케이션 컨텍스트 시작을 실패한다")
	fun worker와_파티션_수_0_이하_애플리케이션_컨텍스트_시작을_실패한다() {
		listOf("worker-count", "collection-partition-count", "credit-partition-count").forEach { propertyName ->
			contextRunner
				.withPropertyValues("japda.batch.daily-seller-settlement.$propertyName=0")
				.run { context -> assertNotNull(context.startupFailure) }
		}
	}

	@Test
	@DisplayName("파티션 수가 worker 수보다 작으면 애플리케이션 컨텍스트 시작을 실패한다")
	fun 파티션_수_worker_수보다_작음_애플리케이션_컨텍스트_시작을_실패한다() {
		listOf("collection-partition-count", "credit-partition-count").forEach { propertyName ->
			contextRunner
				.withPropertyValues(
					"japda.batch.daily-seller-settlement.worker-count=4",
					"japda.batch.daily-seller-settlement.$propertyName=3",
				)
				.run { context -> assertNotNull(context.startupFailure) }
		}
	}

	@Test
	@DisplayName("외부 설정을 chunk, page와 fetch 처리 단위에 바인딩한다")
	fun 외부_설정_chunk_page_fetch_처리_단위에_바인딩한다() {
		contextRunner
			.withPropertyValues(
				"japda.batch.daily-seller-settlement.chunk-size=11",
				"japda.batch.daily-seller-settlement.page-size=12",
				"japda.batch.daily-seller-settlement.fetch-size=13",
			)
			.run { context ->
				val properties = context.getBean(DailySellerSettlementBatchProperties::class.java)

				assertEquals(11, properties.chunkSize)
				assertEquals(12, properties.pageSize)
				assertEquals(13, properties.fetchSize)
			}
	}

	@Test
	@DisplayName("처리 단위가 0 이하이면 애플리케이션 컨텍스트 시작을 실패한다")
	fun 처리_단위_0_이하_애플리케이션_컨텍스트_시작을_실패한다() {
		listOf("chunk-size", "page-size", "fetch-size").forEach { propertyName ->
			contextRunner
				.withPropertyValues("japda.batch.daily-seller-settlement.$propertyName=0")
				.run { context ->
					assertNotNull(context.startupFailure)
				}
		}
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(DailySellerSettlementBatchProperties::class)
	private class PropertiesConfiguration
}
