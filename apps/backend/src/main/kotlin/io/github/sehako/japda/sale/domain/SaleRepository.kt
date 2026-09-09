package io.github.sehako.japda.sale.domain

interface SaleRepository {

	fun save(sale: Sale): Sale
}
