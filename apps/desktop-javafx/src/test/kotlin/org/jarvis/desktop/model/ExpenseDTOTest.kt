package org.jarvis.desktop.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ExpenseDTOTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ExpenseDTO serializes BigDecimal amount as a JSON string`() {
        val expense = ExpenseDTO(
            id = 42L,
            amount = BigDecimal("19.99"),
            currency = "PLN",
            category = "food",
            description = "lunch",
            userId = "alice",
            createdAt = "2026-07-04T10:00:00Z"
        )

        val encoded = json.encodeToString(expense)
        assertEquals(
            """{"id":42,"amount":"19.99","currency":"PLN","category":"food","description":"lunch","userId":"alice","createdAt":"2026-07-04T10:00:00Z"}""",
            encoded
        )
    }

    @Test
    fun `ExpenseDTO round trips through JSON`() {
        val original = ExpenseDTO(amount = BigDecimal("5.50"), currency = "USD", category = "transport")
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString(ExpenseDTO.serializer(), encoded)

        assertEquals(original.amount, decoded.amount)
        assertEquals(original.currency, decoded.currency)
        assertEquals(original.category, decoded.category)
        assertNull(decoded.id)
        assertNull(decoded.description)
    }

    @Test
    fun `BigDecimalSerializer decodes a plain numeric string back into BigDecimal`() {
        val decoded = json.decodeFromString(
            ExpenseDTO.serializer(),
            """{"amount":"100.00","currency":"EUR","category":"rent"}"""
        )
        assertEquals(0, BigDecimal("100.00").compareTo(decoded.amount))
    }
}
