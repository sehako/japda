package io.github.sehako.japda.ledger.infrastructure.config

import io.github.sehako.japda.ledger.application.service.CreditWalletService
import io.github.sehako.japda.ledger.domain.repository.LedgerEntryRepository
import io.github.sehako.japda.ledger.domain.repository.WalletRepository
import io.github.sehako.japda.ledger.infrastructure.persistence.JdbcLedgerEntryRepository
import io.github.sehako.japda.ledger.infrastructure.persistence.JdbcWalletRepository
import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

@Configuration(proxyBeanMethods = false)
open class LedgerConfiguration {
	@Bean
	open fun walletRepository(jdbcTemplate: JdbcTemplate): WalletRepository = JdbcWalletRepository(jdbcTemplate)

	@Bean
	open fun ledgerEntryRepository(jdbcTemplate: JdbcTemplate): LedgerEntryRepository = JdbcLedgerEntryRepository(jdbcTemplate)

	@Bean
	open fun creditWalletService(
		walletRepository: WalletRepository,
		ledgerEntryRepository: LedgerEntryRepository,
		clock: Clock,
	): CreditWalletService = CreditWalletService(walletRepository, ledgerEntryRepository, clock)
}
