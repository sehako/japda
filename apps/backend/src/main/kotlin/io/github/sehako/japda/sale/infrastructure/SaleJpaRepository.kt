package io.github.sehako.japda.sale.infrastructure

import io.github.sehako.japda.sale.domain.Sale
import org.springframework.data.jpa.repository.JpaRepository

interface SaleJpaRepository : JpaRepository<Sale, Long>
