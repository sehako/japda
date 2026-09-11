package io.github.sehako.japda.order.exception

class OrderIdempotencyPersistenceException(
	cause: Throwable,
) : RuntimeException(cause)
