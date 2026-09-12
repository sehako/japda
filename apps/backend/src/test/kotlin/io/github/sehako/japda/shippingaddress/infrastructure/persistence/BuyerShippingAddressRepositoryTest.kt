package io.github.sehako.japda.shippingaddress.infrastructure.persistence

import io.github.sehako.japda.shippingaddress.domain.model.BuyerShippingAddress
import io.github.sehako.japda.shippingaddress.domain.repository.BuyerShippingAddressBookRepository
import io.github.sehako.japda.shippingaddress.domain.repository.BuyerShippingAddressRepository
import io.github.sehako.japda.shippingaddress.exception.BuyerShippingAddressNameDuplicatedPersistenceException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
@Import(BuyerShippingAddressBookRepositoryImpl::class, BuyerShippingAddressRepositoryImpl::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("구매자 배송지 영속성")
class BuyerShippingAddressRepositoryTest {
	@Autowired
	private lateinit var bookRepository: BuyerShippingAddressBookRepository

	@Autowired
	private lateinit var addressRepository: BuyerShippingAddressRepository

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@BeforeEach
	fun 테스트_데이터를_초기화한다() {
		jdbcTemplate.update("DELETE FROM buyer_shipping_addresses")
		jdbcTemplate.update("DELETE FROM buyer_shipping_address_books")
	}

	@Test
	@DisplayName("같은 구매자의 book은 한 행만 생성한다")
	fun 같은_구매자_book_한_행만_생성한다() {
		bookRepository.createIfAbsent(123L, NOW)
		bookRepository.createIfAbsent(123L, NOW.plusSeconds(1))

		val book = assertNotNull(bookRepository.findByBuyerIdForUpdate(123L))
		assertEquals(NOW, book.createdAt)
		assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM buyer_shipping_address_books", Int::class.java))
	}

	@Test
	@DisplayName("같은 book의 배송지명 unique 충돌만 전용 persistence 오류로 변환한다")
	fun 같은_book_배송지명_unique_충돌만_전용_persistence_오류로_변환한다() {
		val bookId = createBook(123L)
		addressRepository.save(address(bookId, "집"))

		assertFailsWith<BuyerShippingAddressNameDuplicatedPersistenceException> {
			addressRepository.save(address(bookId, "집"))
		}
	}

	@Test
	@DisplayName("서로 다른 book에는 같은 배송지명을 저장한다")
	fun 서로_다른_book_같은_배송지명을_저장한다() {
		addressRepository.save(address(createBook(123L), "집"))
		addressRepository.save(address(createBook(124L), "집"))

		assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM buyer_shipping_addresses", Int::class.java))
	}

	@Test
	@DisplayName("존재하지 않는 book FK 오류는 배송지명 중복 오류로 변환하지 않는다")
	fun 존재하지_않는_book_FK_오류_배송지명_중복_오류로_변환하지_않는다() {
		assertFailsWith<DataIntegrityViolationException> {
			addressRepository.save(address(Long.MAX_VALUE, "집"))
		}
	}

	private fun createBook(buyerId: Long): Long {
		bookRepository.createIfAbsent(buyerId, NOW)
		return requireNotNull(bookRepository.findByBuyerIdForUpdate(buyerId)?.id)
	}

	private fun address(bookId: Long, name: String) = BuyerShippingAddress.create(
		bookId,
		name,
		"홍길동",
		"010",
		"06236",
		"서울",
		"101호",
		null,
		NOW,
	)

	private companion object {
		val NOW: Instant = Instant.parse("2026-09-12T03:34:56Z")

		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
