package io.github.sehako.japda.order.infrastructure.checkout.cache.config

import io.github.sehako.japda.order.domain.repository.CheckoutProductSnapshotQuery
import io.github.sehako.japda.order.infrastructure.persistence.CheckoutJpaRepository
import io.github.sehako.japda.order.infrastructure.persistence.CheckoutProductSnapshotProjection
import io.github.sehako.japda.order.infrastructure.persistence.CheckoutProductSnapshotQueryRepositoryImpl
import io.github.sehako.japda.order.infrastructure.persistence.CheckoutShippingAddressProjection
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.redis.connection.RedisConnectionFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("체크아웃 상품 스냅샷 캐시 bean 구성")
class CheckoutProductSnapshotCacheConfigurationTest {
	private val contextRunner = ApplicationContextRunner()
		.withInitializer(ConfigDataApplicationContextInitializer())
		.withUserConfiguration(TestConfiguration::class.java)

	@Test
	fun `기본값은_Redis_연결_없이_PostgreSQL_상품_스냅샷_조회_구현을_선택한다`() {
		contextRunner.run { context ->
			assertEquals(0, context.getBeansOfType(RedisConnectionFactory::class.java).size)
			assertTrue(context.getBean(CheckoutProductSnapshotQuery::class.java) is CheckoutProductSnapshotQueryRepositoryImpl)
		}
	}

	@Test
	fun `캐시를_활성화하면_Redis_상품_스냅샷_조회_구현을_선택한다`() {
		contextRunner
			.withPropertyValues("order.checkout.cache.enabled=true")
			.run { context ->
				assertEquals(1, context.getBeansOfType(RedisConnectionFactory::class.java).size)
				assertEquals(
					"RedisCheckoutProductSnapshotQuery",
					context.getBean(CheckoutProductSnapshotQuery::class.java)::class.simpleName,
				)
			}
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class])
	@Import(CheckoutProductSnapshotCacheConfiguration::class, CheckoutProductSnapshotQueryRepositoryImpl::class)
	class TestConfiguration {
		@Bean
		fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()

		@Bean
		internal fun checkoutJpaRepository(): CheckoutJpaRepository = object : CheckoutJpaRepository {
			override fun findProductSnapshot(saleId: Long): CheckoutProductSnapshotProjection? = null

			override fun findShippingAddresses(buyerId: Long): List<CheckoutShippingAddressProjection> = emptyList()
		}
	}
}
