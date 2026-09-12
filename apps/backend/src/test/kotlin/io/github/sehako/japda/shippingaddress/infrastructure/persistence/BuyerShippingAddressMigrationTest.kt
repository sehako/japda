package io.github.sehako.japda.shippingaddress.infrastructure.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("구매자 배송지 migration")
class BuyerShippingAddressMigrationTest {
	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Test
	@DisplayName("book과 배송지 컬럼 길이 및 제약을 생성한다")
	fun book과_배송지_컬럼_길이와_제약을_생성한다() {
		val lengths = jdbcTemplate.queryForList(
			"""SELECT column_name, character_maximum_length FROM information_schema.columns
				WHERE table_name = 'buyer_shipping_addresses' AND character_maximum_length IS NOT NULL""",
		).associate { it["column_name"] to it["character_maximum_length"] }

		assertEquals(100, lengths["address_name"])
		assertEquals(255, lengths["address"])
		assertEquals(500, lengths["delivery_message"])
		assertFailsWith<DataIntegrityViolationException> {
			jdbcTemplate.update("INSERT INTO buyer_shipping_address_books (buyer_id, created_at) VALUES (0, now())")
		}
	}

	companion object {
		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
