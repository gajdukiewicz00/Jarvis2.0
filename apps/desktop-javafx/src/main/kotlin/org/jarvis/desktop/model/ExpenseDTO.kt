package org.jarvis.desktop.model

import kotlinx.serialization.Serializable
import java.math.BigDecimal

/**
 * Expense data class for API communication.
 * Maps to ExpenseDTO from life-tracker service.
 */
@Serializable
data class ExpenseDTO(
    val id: Long? = null,
    @Serializable(with = BigDecimalSerializer::class)
    val amount: BigDecimal,
    val currency: String,
    val category: String,
    val description: String? = null,
    val userId: String? = null,
    val createdAt: String? = null
)

/**
 * Custom serializer for BigDecimal since kotlinx.serialization doesn't support it natively.
 */
object BigDecimalSerializer : kotlinx.serialization.KSerializer<BigDecimal> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("BigDecimal", kotlinx.serialization.descriptors.PrimitiveKind.STRING)
    
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: BigDecimal) {
        encoder.encodeString(value.toString())
    }
    
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): BigDecimal {
        return BigDecimal(decoder.decodeString())
    }
}
