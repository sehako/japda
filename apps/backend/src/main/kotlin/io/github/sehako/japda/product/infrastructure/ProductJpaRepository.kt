package io.github.sehako.japda.product.infrastructure

import io.github.sehako.japda.product.domain.Product
import org.springframework.data.jpa.repository.JpaRepository

interface ProductJpaRepository : JpaRepository<Product, Long>
