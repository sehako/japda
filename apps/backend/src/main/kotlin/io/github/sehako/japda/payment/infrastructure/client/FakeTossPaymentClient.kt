package io.github.sehako.japda.payment.infrastructure.client

import io.github.sehako.japda.payment.application.client.TossPaymentClient
import io.github.sehako.japda.payment.application.client.TossPaymentResult
import io.github.sehako.japda.payment.application.client.TossPaymentStatuses
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "payment.toss", name = ["client-mode"], havingValue = "fake", matchIfMissing = true)
class FakeTossPaymentClient(
	@Value("\${payment.toss.fake-response-delay:PT0.2S}") private val responseDelay: Duration,
	private val clock: Clock,
) : TossPaymentClient {
	override fun confirm(paymentKey: String, orderId: String, amount: Long, idempotencyKey: String): TossPaymentResult = try {
		TimeUnit.NANOSECONDS.sleep(responseDelay.toNanos())
		TossPaymentResult.Record(paymentKey, orderId, amount, TossPaymentStatuses.DONE, clock.instant())
	} catch (_: InterruptedException) {
		Thread.currentThread().interrupt()
		TossPaymentResult.Unavailable
	}

	override fun lookup(paymentKey: String): TossPaymentResult = TossPaymentResult.NotFound
}
