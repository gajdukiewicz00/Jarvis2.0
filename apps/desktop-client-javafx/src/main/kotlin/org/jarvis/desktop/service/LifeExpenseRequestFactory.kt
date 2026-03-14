package org.jarvis.desktop.service

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jarvis.desktop.model.ExpenseDTO
import java.math.BigDecimal

class LifeExpenseRequestFactory(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
) {
    fun create(
        amount: BigDecimal,
        currency: String,
        category: String,
        description: String?,
        userId: String?
    ): String {
        return json.encodeToString(
            ExpenseDTO(
                amount = amount,
                currency = currency,
                category = category,
                description = description?.trim()?.takeIf { it.isNotEmpty() },
                userId = userId?.trim()?.takeIf { it.isNotEmpty() }
            )
        )
    }
}
