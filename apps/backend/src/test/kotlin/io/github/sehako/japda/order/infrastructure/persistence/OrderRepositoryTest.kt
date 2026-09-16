package io.github.sehako.japda.order.infrastructure.persistence

import io.github.sehako.japda.order.domain.model.Order
import io.github.sehako.japda.order.domain.model.OrderStatus
import io.github.sehako.japda.order.domain.repository.OrderRepository
import io.github.sehako.japda.order.domain.model.OrderRequest
import io.github.sehako.japda.order.exception.OrderIdempotencyPersistenceException
import io.github.sehako.japda.payment.domain.model.Payment
import io.github.sehako.japda.payment.domain.repository.PaymentRepository
import io.github.sehako.japda.payment.infrastructure.persistence.PaymentRepositoryImpl
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OrderRepositoryImpl::class, PaymentRepositoryImpl::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("주문 영속성")
class OrderRepositoryTest {
	@Autowired
	private lateinit var orderRepository: OrderRepository

	@Autowired
	private lateinit var paymentRepository: PaymentRepository

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	private var saleId: Long = 0

	@BeforeEach
	fun 테스트_데이터를_초기화한다() {
		jdbcTemplate.update("DELETE FROM payments")
		jdbcTemplate.update("DELETE FROM orders")
		jdbcTemplate.update("DELETE FROM sales")
		jdbcTemplate.update("DELETE FROM sale_days")
		jdbcTemplate.update("DELETE FROM product_images")
		jdbcTemplate.update("DELETE FROM products")
		val productId = jdbcTemplate.queryForObject(
			"INSERT INTO products (seller_id, name, status, created_at) VALUES (1, '상품', 'READY', now()) RETURNING id",
			Long::class.java,
		)!!
		jdbcTemplate.update("INSERT INTO sale_days (sale_date, capacity, registered_count) VALUES (?, 20, 1)", SALE_DATE)
		saleId = jdbcTemplate.queryForObject(
			"""INSERT INTO sales (product_id, seller_id, sale_date, price, quantity, created_at)
				VALUES (?, 1, ?, 35000, 10, now()) RETURNING id""",
			Long::class.java,
			productId,
			SALE_DATE,
		)!!
	}

	@Test
	@DisplayName("주문을 저장하고 구매자와 멱등성 키로 조회한다")
	fun 주문_저장_구매자와_멱등성_키로_조회한다() {
		val saved = orderRepository.save(order())

		val found = orderRepository.findByBuyerIdAndIdempotencyKey(123L, IDEMPOTENCY_KEY)

		assertEquals(saved.id, found?.id)
		assertEquals(saved.paymentOrderId, requireNotNull(found).paymentOrderId)
		assertEquals("홍길동", found.shippingAddress.recipientName)
	}

	@Test
	@DisplayName("잠금 전 판매 일정 식별자만 조회하면 주문 Entity를 로딩하지 않는다")
	fun 잠금_전_판매_일정_식별자만_조회한다() {
		val saved = orderRepository.save(order())

		assertEquals(saleId, orderRepository.findSaleIdByPaymentOrderIdAndBuyerId(saved.paymentOrderId, saved.buyerId))
		assertEquals(null, orderRepository.findSaleIdByPaymentOrderIdAndBuyerId(saved.paymentOrderId, 999L))
	}

	@Test
	@DisplayName("결제 주문 식별자는 필수 형식 제약으로 보호한다")
	fun 결제_주문_식별자_필수_형식_제약으로_보호한다() {
		val columns = jdbcTemplate.queryForList(
			"SELECT is_nullable, character_maximum_length FROM information_schema.columns WHERE table_name = 'orders' AND column_name = 'payment_order_id'",
		)
		assertEquals("NO", columns.single()["is_nullable"])
		assertEquals(64, columns.single()["character_maximum_length"])

		orderRepository.save(order())
		assertFailsWith<DataIntegrityViolationException> {
			jdbcTemplate.update(
				"UPDATE orders SET payment_order_id = ? WHERE id = (SELECT id FROM orders LIMIT 1)",
				"허용되지 않는 값!",
			)
		}
	}

	@Test
	@DisplayName("같은 결제 주문 식별자 저장은 멱등성 오류가 아닌 DB unique 오류로 거절한다")
	fun 같은_결제_주문_식별자_저장_멱등성_오류가_아닌_DB_unique_오류로_거절한다() {
		val saved = orderRepository.save(order())
		val duplicate = order(buyerId = 124L, idempotencyKey = UUID.randomUUID())
		setPaymentOrderId(duplicate, saved.paymentOrderId)

		assertFailsWith<DataIntegrityViolationException> {
			orderRepository.save(duplicate)
		}
	}

	@Test
	@DisplayName("서로 다른 주문의 같은 결제 키는 DB 제약으로 거절한다")
	fun 결제_키_유일성_제약을_적용한다() {
		val first = orderRepository.save(order(idempotencyKey = UUID.randomUUID()))
		val second = orderRepository.save(order(idempotencyKey = UUID.randomUUID()))
		paymentRepository.save(Payment.create(requireNotNull(first.id), "shared-key", first.totalPrice, NOW))

		assertFailsWith<DataIntegrityViolationException> {
			paymentRepository.save(Payment.create(requireNotNull(second.id), "shared-key", second.totalPrice, NOW))
		}
	}

	@Test
	@DisplayName("한 주문에는 결제 시도 하나만 저장할 수 있다")
	fun 한_주문_결제_시도_하나만_저장한다() {
		val saved = orderRepository.save(order(idempotencyKey = UUID.randomUUID()))
		paymentRepository.save(Payment.create(requireNotNull(saved.id), "first-key", saved.totalPrice, NOW))

		assertFailsWith<DataIntegrityViolationException> {
			paymentRepository.save(Payment.create(requireNotNull(saved.id), "second-key", saved.totalPrice, NOW))
		}
	}

	@Test
	@DisplayName("구매자와 멱등성 키 유일성 충돌만 전용 persistence 오류로 변환한다")
	fun 구매자와_멱등성_키_유일성_충돌만_전용_persistence_오류로_변환한다() {
		orderRepository.save(order())

		assertFailsWith<OrderIdempotencyPersistenceException> {
			orderRepository.save(order())
		}
	}

	@Test
	@DisplayName("결제 대기 주문만 결제 완료로 변경한다")
	fun 결제_대기_주문만_결제_완료로_변경한다() {
		val saved = orderRepository.save(order())

		assertTrue(orderRepository.markPaidIfPending(requireNotNull(saved.id)))
		assertEquals(OrderStatus.PAID, orderRepository.findById(requireNotNull(saved.id))?.status)
		assertEquals(false, orderRepository.markPaidIfPending(requireNotNull(saved.id)))
	}

	@Test
	@DisplayName("존재하지 않는 판매 일정 FK 오류는 멱등성 오류로 변환하지 않는다")
	fun 존재하지_않는_판매_일정_FK_오류_멱등성_오류로_변환하지_않는다() {
		assertFailsWith<DataIntegrityViolationException> {
			orderRepository.save(order(saleId = Long.MAX_VALUE))
		}
	}

	@Test
	@DisplayName("양수가 아닌 구매자와 수량은 DB check 제약으로 거절한다")
	fun 양수가_아닌_구매자와_수량_DB_check_제약으로_거절한다() {
		assertFailsWith<DataIntegrityViolationException> {
			jdbcTemplate.update(
				"""INSERT INTO orders (
					sale_id, buyer_id, idempotency_key, payment_order_id, quantity, product_name, unit_price, total_price, status,
					recipient_name, phone_number, postal_code, address, detail_address, created_at, expires_at
				) VALUES (?, 0, ?, ?, 0, '상품', 35000, 35000, 'PENDING_PAYMENT', '홍길동', '010', '06236', '서울', '101호', ?, ?)""",
				saleId,
				UUID.randomUUID(),
				UUID.randomUUID().toString(),
				java.sql.Timestamp.from(NOW),
				java.sql.Timestamp.from(NOW.plusSeconds(180)),
			)
		}
	}

	@Test
	@DisplayName("유효 예약 집계를 위한 부분 인덱스가 생성된다")
	fun 유효_예약_집계_부분_인덱스가_생성된다() {
		val definition = jdbcTemplate.queryForObject(
			"SELECT indexdef FROM pg_indexes WHERE tablename = 'orders' AND indexname = 'orders_active_reservation_idx'",
			String::class.java,
		)!!

		assertTrue(definition.contains("(sale_id, expires_at)"))
		assertTrue(definition.contains("status"))
		assertTrue(definition.contains("PENDING_PAYMENT"))
	}

	private fun order(
		saleId: Long = this.saleId,
		buyerId: Long = 123L,
		idempotencyKey: UUID = IDEMPOTENCY_KEY,
	): Order = Order.create(
		OrderRequest.create(
			buyerId,
			idempotencyKey,
			saleId,
			2,
			"홍길동",
			"010-1234-5678",
			"06236",
			"서울시 강남구",
			"101호",
			null,
		),
		"상품",
		35_000L,
		NOW,
	)

	private fun setPaymentOrderId(order: Order, paymentOrderId: String) {
		order::class.java.getDeclaredField("paymentOrderId").apply {
			isAccessible = true
			set(order, paymentOrderId)
		}
	}

	private companion object {
		val SALE_DATE: LocalDate = LocalDate.parse("2026-09-11")
		val NOW: Instant = Instant.parse("2026-09-11T06:00:00Z")
		val IDEMPOTENCY_KEY: UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")

		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
