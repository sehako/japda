package io.github.sehako.japda.payment.infrastructure.client

import io.github.sehako.japda.payment.application.client.TossPaymentClient
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.jupiter.api.DisplayName
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

@DisplayName("토스 결제 Client Bean 선택")
class TossPaymentClientBeanSelectionTest {
	private val contextRunner = ApplicationContextRunner()
		.withInitializer { context ->
			context.beanFactory.conversionService = ApplicationConversionService.getSharedInstance()
		}
		.withUserConfiguration(
			TossPaymentHttpClient::class.java,
			FakeTossPaymentClient::class.java,
			TestConfiguration::class.java,
		)

	@Test
	@DisplayName("client mode 설정이 없으면 Fake Client만 생성한다")
	fun client_mode_설정_없음_Fake_Client만_생성한다() {
		contextRunner.run { context ->
			assertNull(context.startupFailure)
			assertIs<FakeTossPaymentClient>(context.getBeansOfType(TossPaymentClient::class.java).values.single())
		}
	}

	@Test
	@DisplayName("client mode가 fake이면 Fake Client만 생성한다")
	fun client_mode_fake_Fake_Client만_생성한다() {
		contextRunner.withPropertyValues("payment.toss.client-mode=fake").run { context ->
			assertNull(context.startupFailure)
			assertIs<FakeTossPaymentClient>(context.getBeansOfType(TossPaymentClient::class.java).values.single())
		}
	}

	@Test
	@DisplayName("client mode가 real이면 HTTP Client만 생성한다")
	fun client_mode_real_HTTP_Client만_생성한다() {
		contextRunner.withPropertyValues("payment.toss.client-mode=real").run { context ->
			assertNull(context.startupFailure)
			assertIs<TossPaymentHttpClient>(context.getBeansOfType(TossPaymentClient::class.java).values.single())
		}
	}

	@Test
	@DisplayName("client mode가 잘못된 값이면 Client를 생성하지 않는다")
	fun client_mode_잘못된_값_Client를_생성하지_않는다() {
		contextRunner.withPropertyValues("payment.toss.client-mode=invalid").run { context ->
			assertNull(context.startupFailure)
			assertEquals(0, context.getBeansOfType(TossPaymentClient::class.java).size)
		}
	}

	@Configuration(proxyBeanMethods = false)
	class TestConfiguration {
		@Bean
		fun clock(): Clock = Clock.systemUTC()

		@Bean
		fun objectMapper(): ObjectMapper = JsonMapper.builder().build()
	}
}
