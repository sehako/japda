package io.github.sehako.japda.shippingaddress.application.service

import io.github.sehako.japda.shippingaddress.application.dto.CreateBuyerShippingAddressDto
import io.github.sehako.japda.shippingaddress.domain.model.BuyerShippingAddressBook
import io.github.sehako.japda.shippingaddress.domain.model.BuyerShippingAddressDetails
import io.github.sehako.japda.shippingaddress.domain.repository.BuyerShippingAddressBookRepository
import io.github.sehako.japda.shippingaddress.domain.repository.BuyerShippingAddressRepository
import io.github.sehako.japda.shippingaddress.exception.BuyerShippingAddressErrorCode
import io.github.sehako.japda.shippingaddress.exception.BuyerShippingAddressException
import io.github.sehako.japda.shippingaddress.exception.BuyerShippingAddressNameDuplicatedPersistenceException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

@DisplayName("구매자 배송지 등록 서비스")
class BuyerShippingAddressServiceTest {
	@Test
	@DisplayName("입력 검증 실패는 transaction 실행 전에 반환한다")
	fun 입력_검증_실패_transaction_실행_전_반환한다() {
		val transactionService = mock(BuyerShippingAddressRegistrationTransactionService::class.java)
		val service = BuyerShippingAddressService(transactionService, Clock.fixed(NOW, ZoneOffset.UTC))

		val exception = assertFailsWith<BuyerShippingAddressException> {
			service.create(DTO.copy(addressName = " "))
		}

		assertEquals(BuyerShippingAddressErrorCode.NAME_INVALID, exception.errorCode)
		verifyNoInteractions(transactionService)
	}

	@Test
	@DisplayName("named unique persistence 충돌은 배송지명 중복 오류로 변환한다")
	fun named_unique_persistence_충돌_배송지명_중복_오류로_변환한다() {
		val book = BuyerShippingAddressBook.create(123L, NOW)
		setId(book, 1L)
		val bookRepository = object : BuyerShippingAddressBookRepository {
			override fun createIfAbsent(buyerId: Long, createdAt: Instant) = Unit
			override fun findByBuyerIdForUpdate(buyerId: Long): BuyerShippingAddressBook = book
		}
		val addressRepository = object : BuyerShippingAddressRepository {
			override fun countByBuyerShippingAddressBookId(bookId: Long): Long = 0
			override fun existsByBuyerShippingAddressBookIdAndAddressName(bookId: Long, addressName: String): Boolean = false
			override fun save(address: io.github.sehako.japda.shippingaddress.domain.model.BuyerShippingAddress): Nothing =
				throw BuyerShippingAddressNameDuplicatedPersistenceException(IllegalStateException())
		}
		val transactionService = BuyerShippingAddressRegistrationTransactionService(bookRepository, addressRepository)
		val service = BuyerShippingAddressService(transactionService, Clock.fixed(NOW, ZoneOffset.UTC))

		val exception = assertFailsWith<BuyerShippingAddressException> { service.create(DTO) }

		assertEquals(BuyerShippingAddressErrorCode.NAME_DUPLICATED, exception.errorCode)
	}

	@Test
	@DisplayName("배송지가 10개이면 배송지명 중복 조회보다 개수 초과를 우선한다")
	fun 배송지_10개_배송지명_중복_조회보다_개수_초과를_우선한다() {
		val bookRepository = mock(BuyerShippingAddressBookRepository::class.java)
		val addressRepository = mock(BuyerShippingAddressRepository::class.java)
		val transactionService = BuyerShippingAddressRegistrationTransactionService(bookRepository, addressRepository)
		val book = BuyerShippingAddressBook.create(123L, NOW)
		setId(book, 1L)
		`when`(bookRepository.findByBuyerIdForUpdate(123L)).thenReturn(book)
		`when`(addressRepository.countByBuyerShippingAddressBookId(1L)).thenReturn(10L)
		val details = BuyerShippingAddressDetails.create("집", "홍길동", "010", "06236", "서울", "101호", null)

		val exception = assertFailsWith<BuyerShippingAddressException> {
			transactionService.register(123L, details, NOW)
		}

		assertEquals(BuyerShippingAddressErrorCode.LIMIT_EXCEEDED, exception.errorCode)
		verify(bookRepository).createIfAbsent(123L, NOW)
		verify(addressRepository, never()).existsByBuyerShippingAddressBookIdAndAddressName(1L, "집")
	}

	private fun setId(book: BuyerShippingAddressBook, id: Long) {
		book::class.java.getDeclaredField("id").apply {
			isAccessible = true
			set(book, id)
		}
	}

	private companion object {
		val NOW: Instant = Instant.parse("2026-09-12T03:34:56Z")
		val DTO = CreateBuyerShippingAddressDto(123L, "집", "홍길동", "010", "06236", "서울", "101호", null)
	}
}
