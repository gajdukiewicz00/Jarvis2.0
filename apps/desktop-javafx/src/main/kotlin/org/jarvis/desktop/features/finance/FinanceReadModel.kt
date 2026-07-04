package org.jarvis.desktop.features.finance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.model.ExpenseDTO
import org.jarvis.desktop.service.LifeExpenseRequestFactory
import java.math.BigDecimal

/**
 * Read model for the Finance panel.
 *
 * Wires the life-tracker finance surface:
 *  - parse a bank notification -> POST /api/v1/life/finance/parse-notification
 *  - list recent transactions  -> GET  /api/v1/life/finance/expenses
 *  - add a manual expense       -> POST /api/v1/life/finance/expenses
 *
 * Reuses the existing [LifeExpenseRequestFactory] and [ExpenseDTO] so the add
 * path stays identical to the legacy Life tab.
 */
class FinanceReadModel(
    private val apiClient: ApiClient
) {
    private val objectMapper = jacksonObjectMapper()
    private val expenseFactory = LifeExpenseRequestFactory()
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    data class Transaction(
        val amount: String,
        val currency: String,
        val category: String,
        val description: String,
        val createdAt: String
    )

    data class ParsedNotification(
        val amount: String,
        val currency: String,
        val category: String,
        val merchant: String,
        val raw: String
    )

    fun listTransactions(): List<Transaction> {
        val response = apiClient.get("/life/finance/expenses")
        val expenses = runCatching { json.decodeFromString<List<ExpenseDTO>>(response) }.getOrElse { emptyList() }
        return expenses.reversed().take(20).map { expense ->
            Transaction(
                amount = expense.amount.toPlainString(),
                currency = expense.currency,
                category = expense.category,
                description = expense.description ?: "",
                createdAt = expense.createdAt ?: ""
            )
        }
    }

    fun parseNotification(text: String): ParsedNotification {
        val payload = objectMapper.createObjectNode().apply {
            put("text", text.trim())
            put("notification", text.trim())
        }
        val response = apiClient.post("/life/finance/parse-notification", objectMapper.writeValueAsString(payload))
        val root = objectMapper.readTree(response)
        return ParsedNotification(
            amount = firstNonBlank(root.path("amount").textOrNull(), root.at("/expense/amount").textOrNull()) ?: "?",
            currency = firstNonBlank(root.path("currency").textOrNull(), root.at("/expense/currency").textOrNull()) ?: "",
            category = firstNonBlank(root.path("category").textOrNull(), root.at("/expense/category").textOrNull()) ?: "uncategorized",
            merchant = firstNonBlank(
                root.path("merchant").textOrNull(),
                root.path("description").textOrNull(),
                root.at("/expense/description").textOrNull()
            ) ?: "",
            raw = response
        )
    }

    fun addExpense(amount: BigDecimal, currency: String, category: String, description: String?): Transaction {
        val body = expenseFactory.create(
            amount = amount,
            currency = currency,
            category = category,
            description = description,
            userId = null
        )
        apiClient.post("/life/finance/expenses", body)
        return Transaction(
            amount = amount.toPlainString(),
            currency = currency,
            category = category,
            description = description ?: "",
            createdAt = ""
        )
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText(null)?.takeIf { it.isNotBlank() }
}
