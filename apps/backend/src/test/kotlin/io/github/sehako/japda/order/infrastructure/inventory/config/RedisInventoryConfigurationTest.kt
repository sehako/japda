package io.github.sehako.japda.order.infrastructure.inventory.config

import io.github.sehako.japda.order.application.inventory.InventoryReservation
import io.github.sehako.japda.order.application.inventory.InventoryReservationResult
import io.github.sehako.japda.order.application.inventory.snapshot.InventorySnapshotService
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.redis.connection.RedisConnectionFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@DisplayName("Redis 재고 bean 구성")
class RedisInventoryConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withInitializer(ConfigDataApplicationContextInitializer())
        .withUserConfiguration(TestConfiguration::class.java)

    @Test
    fun `기본값은_Redis_연결_없이_fallback_구현을_선택한다`() {
        contextRunner.run { context ->
            assertEquals(InventoryReservationResult.Fallback, context.getBean(InventoryReservation::class.java).reserve(1L, 1))
            assertEquals(0, context.getBeansOfType(RedisConnectionFactory::class.java).size)
            assertEquals(false, context.containsBean("redisHealthContributor"))
            assertEquals(false, context.containsBean("redisReactiveHealthContributor"))
            assertEquals("*", context.environment.getProperty("management.endpoints.web.exposure.exclude"))
        }
    }

    @Test
    fun `활성화하면_Lettuce_Redis_구현을_구성한다`() {
        contextRunner
            .withPropertyValues("order.inventory.redis.enabled=true")
            .run { context ->
                assertNotNull(context.getBean(RedisConnectionFactory::class.java))
                assertEquals(setOf("inventoryRedisConnectionFactory"), context.getBeansOfType(RedisConnectionFactory::class.java).keys)
                assertEquals("RedisInventoryReservation", context.getBean(InventoryReservation::class.java)::class.simpleName)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class])
    @Import(RedisInventoryConfiguration::class)
    class TestConfiguration {
        @Bean
        fun inventorySnapshotService(): InventorySnapshotService = mock(InventorySnapshotService::class.java)

        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }
}
