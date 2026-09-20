package io.github.sehako.japda.order.infrastructure.checkout.cache.config

import io.github.sehako.japda.order.domain.repository.CheckoutProductSnapshotQuery
import io.github.sehako.japda.order.infrastructure.checkout.cache.key.RedisCheckoutProductSnapshotKeyFactory
import io.github.sehako.japda.order.infrastructure.checkout.cache.redis.RedisCheckoutProductSnapshotQuery
import io.github.sehako.japda.order.infrastructure.persistence.CheckoutProductSnapshotQueryRepositoryImpl
import io.micrometer.core.instrument.MeterRegistry
import io.lettuce.core.ClientOptions
import io.lettuce.core.SocketOptions
import java.util.concurrent.Executor
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.task.TaskExecutor
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import tools.jackson.databind.ObjectMapper

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisCheckoutProductSnapshotCacheProperties::class)
class CheckoutProductSnapshotCacheConfiguration {
	@Bean
	@ConditionalOnProperty(prefix = PREFIX, name = ["enabled"], havingValue = "true")
	fun checkoutProductSnapshotRedisConnectionFactory(properties: RedisCheckoutProductSnapshotCacheProperties): RedisConnectionFactory {
		val socketOptions = SocketOptions.builder().connectTimeout(properties.connectTimeout).build()
		val clientOptions = ClientOptions.builder().socketOptions(socketOptions).build()
		val clientConfiguration = LettuceClientConfiguration.builder().commandTimeout(properties.commandTimeout).clientOptions(clientOptions).build()
		return LettuceConnectionFactory(RedisStandaloneConfiguration(properties.host, properties.port), clientConfiguration)
	}

	@Bean
	@ConditionalOnProperty(prefix = PREFIX, name = ["enabled"], havingValue = "true")
	fun checkoutProductSnapshotRedisTemplate(checkoutProductSnapshotRedisConnectionFactory: RedisConnectionFactory): StringRedisTemplate =
		StringRedisTemplate(checkoutProductSnapshotRedisConnectionFactory)

	@Bean
	@ConditionalOnProperty(prefix = PREFIX, name = ["enabled"], havingValue = "true")
	fun redisCheckoutProductSnapshotKeyFactory(properties: RedisCheckoutProductSnapshotCacheProperties) = RedisCheckoutProductSnapshotKeyFactory(properties.namespace)

	@Bean
	@ConditionalOnProperty(prefix = PREFIX, name = ["enabled"], havingValue = "true")
	fun checkoutProductSnapshotRefreshExecutor(): TaskExecutor = ThreadPoolTaskExecutor().apply {
		corePoolSize = 1
		maxPoolSize = 1
		setThreadNamePrefix("checkout-snapshot-refresh-")
		initialize()
	}

	@Bean
	@Primary
	@ConditionalOnProperty(prefix = PREFIX, name = ["enabled"], havingValue = "true")
	internal fun redisCheckoutProductSnapshotQuery(
		checkoutProductSnapshotRedisTemplate: StringRedisTemplate,
		productSnapshotSourceQuery: CheckoutProductSnapshotQueryRepositoryImpl,
		keyFactory: RedisCheckoutProductSnapshotKeyFactory,
		properties: RedisCheckoutProductSnapshotCacheProperties,
		objectMapper: ObjectMapper,
		@Qualifier("checkoutProductSnapshotRefreshExecutor") refreshExecutor: Executor,
		meterRegistry: MeterRegistry,
	): CheckoutProductSnapshotQuery = RedisCheckoutProductSnapshotQuery(
		checkoutProductSnapshotRedisTemplate, productSnapshotSourceQuery, keyFactory, properties, objectMapper, refreshExecutor, meterRegistry,
	)

	private companion object { const val PREFIX = "order.checkout.cache" }
}
