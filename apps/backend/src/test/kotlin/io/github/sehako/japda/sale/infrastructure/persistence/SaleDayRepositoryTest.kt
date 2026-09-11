package io.github.sehako.japda.sale.infrastructure.persistence

import io.github.sehako.japda.sale.domain.repository.SaleDayRepository
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SaleDayRepositoryImpl::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("판매일 영속성")
class SaleDayRepositoryTest {
	@Autowired
	private lateinit var saleDayRepository: SaleDayRepository

	@Autowired
	private lateinit var transactionTemplate: TransactionTemplate

	@Test
	@DisplayName("판매일이 없으면 생성하고 이미 있으면 확정 정원을 유지한다")
	fun 판매일_최초_생성_기존_정원을_유지한다() {
		saleDayRepository.createIfAbsent(SALE_DATE, 20)
		saleDayRepository.createIfAbsent(SALE_DATE, 30)

		val saleDay = assertNotNull(saleDayRepository.findBySaleDateForUpdate(SALE_DATE))
		assertEquals(20, saleDay.capacity)
		assertEquals(0, saleDay.registeredCount)
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("한 트랜잭션이 판매일을 잠그면 다른 잠금 조회는 커밋까지 대기한다")
	fun 판매일_쓰기_잠금_다른_잠금_조회는_커밋까지_대기한다() {
		transactionTemplate.execute { saleDayRepository.createIfAbsent(SALE_DATE, 20) }
		val firstLocked = CountDownLatch(1)
		val releaseFirst = CountDownLatch(1)
		val secondFinished = CountDownLatch(1)

		Executors.newFixedThreadPool(2).use { executor ->
			executor.submit {
				transactionTemplate.execute {
					assertNotNull(saleDayRepository.findBySaleDateForUpdate(SALE_DATE))
					firstLocked.countDown()
					releaseFirst.await(5, TimeUnit.SECONDS)
				}
			}
			assertTrue(firstLocked.await(5, TimeUnit.SECONDS))
			executor.submit {
				transactionTemplate.execute { saleDayRepository.findBySaleDateForUpdate(SALE_DATE) }
				secondFinished.countDown()
			}

			assertFalse(secondFinished.await(300, TimeUnit.MILLISECONDS))
			releaseFirst.countDown()
			assertTrue(secondFinished.await(5, TimeUnit.SECONDS))
		}
	}

	companion object {
		private val SALE_DATE = LocalDate.of(2026, 9, 12)

		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
