package io.github.sehako.japda.sale.presentation

import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.deser.std.StdDeserializer

class StrictNullableLongDeserializer : StdDeserializer<Long?>(Long::class.javaObjectType) {
	override fun deserialize(parser: JsonParser, context: DeserializationContext): Long {
		if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
			return context.reportInputMismatch(this, "JSON 정수만 허용됩니다.")
		}
		return parser.longValue
	}
}

class StrictNullableIntDeserializer : StdDeserializer<Int?>(Int::class.javaObjectType) {
	override fun deserialize(parser: JsonParser, context: DeserializationContext): Int {
		if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
			return context.reportInputMismatch(this, "JSON 정수만 허용됩니다.")
		}
		return parser.intValue
	}
}
