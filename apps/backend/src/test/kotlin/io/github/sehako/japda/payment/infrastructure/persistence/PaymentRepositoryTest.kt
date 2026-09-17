package io.github.sehako.japda.payment.infrastructure.persistence

import io.github.sehako.japda.payment.domain.model.PaymentStatus
import io.github.sehako.japda.payment.domain.repository.PaymentRepository
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PaymentRepositoryImpl::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("결제 영속성")
class PaymentRepositoryTest {
	@Autowired private lateinit var payments: PaymentRepository
	@Autowired private lateinit var jdbc: JdbcTemplate

	private var paymentId: Long = 0

	@BeforeEach
	fun 초기화() {
		jdbc.update("DELETE FROM payments")
		jdbc.update("DELETE FROM orders")
		jdbc.update("DELETE FROM sales")
		jdbc.update("DELETE FROM sale_days")
		jdbc.update("DELETE FROM product_images")
		jdbc.update("DELETE FROM products")

		val productId = jdbc.queryForObject(
			"INSERT INTO products (seller_id, name, status, created_at) VALUES (1, '상품', 'READY', ?) RETURNING id",
			Long::class.java,
			Timestamp.from(NOW),
		)!!
		jdbc.update("INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (?, 20, 1)", SALE_DATE)
		val saleId = jdbc.queryForObject(
			"INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at) VALUES (?, 1, ?, 35000, 2, ?) RETURNING id",
			Long::class.java,
			productId,
			SALE_DATE,
			Timestamp.from(NOW),
		)!!
		val orderId = jdbc.queryForObject(
			"""INSERT INTO orders (
				sale_id, buyer_id, idempotency_key, payment_order_id, quantity, product_name, unit_price, total_price, status,
				recipient_name, phone_number, postal_code, address, detail_address, created_at, expires_at
			) VALUES (?, 123, ?, ?, 2, '상품', 35000, 70000, 'PENDING_PAYMENT', '홍길동', '010', '06236', '서울', '101호', ?, ?) RETURNING id""",
			Long::class.java,
			saleId,
			UUID.randomUUID(),
			UUID.randomUUID().toString(),
			Timestamp.from(NOW),
			Timestamp.from(NOW.plusSeconds(180)),
		)!!
		paymentId = jdbc.queryForObject(
			"""INSERT INTO payments (
				order_id, payment_key, toss_idempotency_key, status, requested_amount, created_at,
				first_requested_at, last_checked_at, next_reconcile_at
			) VALUES (?, 'payment-key', ?, 'CONFIRMING', 70000, ?, ?, ?, ?) RETURNING id""",
			Long::class.java,
			orderId,
			UUID.randomUUID().toString(),
			Timestamp.from(NOW),
			Timestamp.from(NOW),
			Timestamp.from(NOW),
			Timestamp.from(NOW.plusSeconds(30)),
		)!!
	}

	@Test
	@DisplayName("승인 중 결제만 승인하고 승인된 결제를 실패로 덮어쓰지 않는다")
	fun 승인_중_결제만_승인하고_실패로_덮어쓰지_않는다() {
		val approvedAt = NOW.plusSeconds(1)

		assertTrue(payments.approveIfConfirming(paymentId, approvedAt))
		assertFalse(payments.failIfConfirming(paymentId, NOW.plusSeconds(2)))

		val payment = payments.findById(paymentId)!!
		assertEquals(PaymentStatus.APPROVED, payment.status)
		assertEquals(approvedAt, payment.approvedAt)
		assertEquals(null, payment.nextReconcileAt)
	}

	@Test
	@DisplayName("재확인 시각이 지난 승인 중 결제는 한 처리자만 선점한다")
	fun 재확인_시각이_지난_승인_중_결제는_한_처리자만_선점한다() {
		val dueAt = NOW.plusSeconds(30)
		val nextReconcileAt = NOW.plusSeconds(60)

		assertFalse(payments.claimIfDue(paymentId, dueAt.minusMillis(1), nextReconcileAt))
		assertTrue(payments.claimIfDue(paymentId, dueAt, nextReconcileAt))
		assertFalse(payments.claimIfDue(paymentId, dueAt, nextReconcileAt.plusSeconds(30)))

		val payment = payments.findById(paymentId)!!
		assertEquals(PaymentStatus.CONFIRMING, payment.status)
		assertEquals(dueAt, payment.lastCheckedAt)
		assertEquals(nextReconcileAt, payment.nextReconcileAt)
	}

	@Test
	@DisplayName("수동 확인으로 전환된 결제는 재확인 대기로 되돌리지 않는다")
	fun 수동_확인으로_전환된_결제는_재확인_대기로_되돌리지_않는다() {
		val reviewedAt = NOW.plusSeconds(31)

		assertTrue(payments.requireReviewIfConfirming(paymentId, reviewedAt))
		assertFalse(payments.deferIfConfirming(paymentId, reviewedAt.plusSeconds(1), reviewedAt.plusSeconds(31)))

		val payment = payments.findById(paymentId)!!
		assertEquals(PaymentStatus.REVIEW_REQUIRED, payment.status)
		assertEquals(reviewedAt, payment.lastCheckedAt)
		assertEquals(null, payment.nextReconcileAt)
	}

	@Test
	@DisplayName("최초 요청이 기준 시각을 지난 승인 중 결제만 수동 확인으로 전환한다")
	fun 최초_요청이_기준_시각을_지난_승인_중_결제만_수동_확인으로_전환한다() {
		assertFalse(payments.requireReviewIfOverdue(paymentId, NOW.minusMillis(1), NOW.plusSeconds(899)))
		assertTrue(payments.requireReviewIfOverdue(paymentId, NOW, NOW.plusSeconds(900)))

		assertEquals(PaymentStatus.REVIEW_REQUIRED, payments.findById(paymentId)!!.status)
	}

	private companion object {
		val NOW: Instant = Instant.parse("2026-09-16T06:00:00Z")
		val SALE_DATE: LocalDate = LocalDate.parse("2026-09-16")

		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
