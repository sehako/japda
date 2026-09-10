package io.github.sehako.japda.product.application

import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.ProductRepository
import io.github.sehako.japda.product.domain.ReadyProductQuery
import io.github.sehako.japda.product.domain.ReadyProductSort
import io.github.sehako.japda.product.exception.ProductErrorCode
import io.github.sehako.japda.product.exception.ProductException
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ProductService(
	private val productRepository: ProductRepository,
	private val clock: Clock,
	private val readyProductCursorCodec: ReadyProductCursorCodec,
) {
	@Transactional
	fun create(dto: CreateProductDto): ProductResponse {
		val product = Product.create(
			sellerId = dto.sellerId,
			name = dto.name,
			description = dto.description,
			createdAt = clock.instant(),
		)

		return productRepository.save(product).toResponse()
	}

	@Transactional(readOnly = true)
	fun listReady(dto: ListReadyProductsDto): ReadyProductPageResponse {
		if (dto.sellerId <= 0) throw ProductException(ProductErrorCode.SELLER_ID_INVALID)
		val sort = dto.sort.toReadyProductSort()
		if (dto.size !in MIN_PAGE_SIZE..MAX_PAGE_SIZE) throw ProductException(ProductErrorCode.PAGE_SIZE_INVALID)
		val boundary = dto.cursor?.let { readyProductCursorCodec.decode(it, sort) }
		val products = productRepository.findReadyProducts(
			ReadyProductQuery(dto.sellerId, sort, boundary, dto.size + 1),
		)
		val hasNext = products.size > dto.size
		val pageItems = if (hasNext) products.take(dto.size) else products
		val nextCursor = if (hasNext) readyProductCursorCodec.encode(sort, pageItems.last()) else null

		return ReadyProductPageResponse(
			items = pageItems.map { ReadyProductResponse(it.id, it.name) },
			nextCursor = nextCursor,
		)
	}

	private fun Product.toResponse(): ProductResponse = ProductResponse(
		id = requireNotNull(id),
		sellerId = sellerId,
		name = name,
		description = description,
		status = status,
		createdAt = createdAt,
	)

	private fun String.toReadyProductSort(): ReadyProductSort = when (this) {
		"latest" -> ReadyProductSort.LATEST
		"oldest" -> ReadyProductSort.OLDEST
		"name-asc" -> ReadyProductSort.NAME_ASC
		"name-desc" -> ReadyProductSort.NAME_DESC
		else -> throw ProductException(ProductErrorCode.SORT_INVALID)
	}

	private companion object {
		const val MIN_PAGE_SIZE = 1
		const val MAX_PAGE_SIZE = 100
	}
}
