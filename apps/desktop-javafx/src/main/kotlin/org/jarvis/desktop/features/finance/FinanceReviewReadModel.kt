package org.jarvis.desktop.features.finance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient

/**
 * Read model for the Finance draft-review inbox.
 *
 * life-tracker's `BankNotificationParser` batch-parses raw notifications
 * (one per line) via `POST /api/v1/life/finance/import-csv-notifications`;
 * LOW/MEDIUM-confidence (or invalid) parses are persisted server-side as
 * `ExpenseDraft` rows (FINANCE-REVIEW) instead of being auto-stored. The
 * persisted review-inbox queue is then paged/edited/approved/rejected via:
 *  - list drafts   -> GET    /api/v1/life/finance/review-inbox?page=&size=
 *  - edit a draft  -> PUT    /api/v1/life/finance/review-inbox/{id}
 *  - approve draft -> POST   /api/v1/life/finance/review-inbox/{id}/approve
 *  - reject draft  -> DELETE /api/v1/life/finance/review-inbox/{id}
 *
 * [parseBatch]/[approve] (the older, purely client-managed queue) are kept
 * for the existing paste-and-approve flow; the imported drafts show up in
 * the persisted queue above regardless of which entry point created them.
 */
class FinanceReviewReadModel(
    private val apiClient: ApiClient
) {
    private val objectMapper = jacksonObjectMapper()

    data class Draft(
        val confidence: String,
        val needsReview: Boolean,
        val amount: String,
        val currency: String,
        val merchant: String,
        val category: String,
        val cardMask: String,
        val occurredAt: String,
        val rawMasked: String,
        val notes: List<String>
    )

    data class BatchResult(
        val imported: Int,
        val totalRows: Int,
        val drafts: List<Draft>
    )

    /** A persisted review-inbox draft (`ExpenseDraftDTO` on the life-tracker side). */
    data class InboxDraft(
        val id: Long,
        val amount: String,
        val currency: String,
        val merchant: String,
        val category: String,
        val confidence: String,
        val status: String,
        val occurredAt: String,
        val notes: String
    )

    data class InboxPage(
        val items: List<InboxDraft>,
        val page: Int,
        val size: Int,
        val totalElements: Long,
        val totalPages: Int
    )

    data class ApprovalOutcome(val duplicate: Boolean, val expenseSummary: String)

    /** [csv] is one raw bank-notification text per line (matches life-tracker's CsvUtils row format). */
    fun parseBatch(csv: String): BatchResult {
        val payload = objectMapper.createObjectNode().apply { put("csv", csv) }
        val root = objectMapper.readTree(
            apiClient.post("/life/finance/import-csv-notifications", objectMapper.writeValueAsString(payload))
        )
        val drafts = root.path("needsReview").takeIf(JsonNode::isArray)?.map(::parseDraft) ?: emptyList()
        return BatchResult(
            imported = root.path("imported").asInt(0),
            totalRows = root.path("totalRows").asInt(0),
            drafts = drafts
        )
    }

    /** Persists an approved (optionally edited) draft as a real expense. */
    fun approve(draft: Draft) {
        val payload = objectMapper.createObjectNode().apply {
            put("amount", draft.amount.toDoubleOrNull() ?: 0.0)
            put("currency", draft.currency.ifBlank { "EUR" })
            put("category", draft.category.ifBlank { "uncategorized" })
            put("description", "bank: " + draft.merchant.ifBlank { "transaction" })
        }
        apiClient.post("/life/finance/expenses", objectMapper.writeValueAsString(payload))
    }

    /** Paged listing of persisted (still-DRAFT) review-inbox drafts. */
    fun listInbox(page: Int = 0, size: Int = 20): InboxPage {
        val root = objectMapper.readTree(
            apiClient.get("/life/finance/review-inbox?page=$page&size=$size")
        )
        val items = root.path("items").takeIf(JsonNode::isArray)?.map(::parseInboxDraft) ?: emptyList()
        return InboxPage(
            items = items,
            page = root.path("page").asInt(page),
            size = root.path("size").asInt(size),
            totalElements = root.path("totalElements").asLong(items.size.toLong()),
            totalPages = root.path("totalPages").asInt(1)
        )
    }

    /** Partial edit of a pending draft — only non-blank fields are sent (server leaves the rest unchanged). */
    fun editInboxDraft(
        id: Long,
        amount: String? = null,
        merchant: String? = null,
        category: String? = null,
        currency: String? = null
    ): InboxDraft {
        val payload = objectMapper.createObjectNode().apply {
            amount?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { put("amount", it.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) }
            merchant?.trim()?.takeIf { it.isNotEmpty() }?.let { put("merchant", it) }
            category?.trim()?.takeIf { it.isNotEmpty() }?.let { put("category", it) }
            currency?.trim()?.takeIf { it.isNotEmpty() }?.let { put("currency", it) }
        }
        val response = apiClient.put("/life/finance/review-inbox/$id", objectMapper.writeValueAsString(payload))
        return parseInboxDraft(objectMapper.readTree(response))
    }

    /** Approves a draft: persists it as a real expense (or resolves to an existing duplicate). */
    fun approveInboxDraft(id: Long): ApprovalOutcome {
        val root = objectMapper.readTree(apiClient.post("/life/finance/review-inbox/$id/approve", "{}"))
        val duplicate = root.path("duplicate").let { it.isBoolean && it.asBoolean() }
        val expense = root.path("expense")
        val summary = listOfNotNull(
            expense.path("amount").textOrNull(),
            expense.path("currency").textOrNull(),
            expense.path("merchant").textOrNull()?.takeIf { it.isNotBlank() }
        ).joinToString(" ")
        return ApprovalOutcome(duplicate, summary)
    }

    /** Discards a pending draft; nothing is ever persisted as an expense. */
    fun rejectInboxDraft(id: Long) {
        apiClient.delete("/life/finance/review-inbox/$id")
    }

    private fun parseInboxDraft(node: JsonNode): InboxDraft {
        return InboxDraft(
            id = node.path("id").asLong(0),
            amount = node.path("amount").textOrNull() ?: (if (node.path("amount").isNumber) node.path("amount").asText() else "0"),
            currency = node.path("currency").textOrNull() ?: "",
            merchant = node.path("merchant").textOrNull() ?: "",
            category = node.path("category").textOrNull() ?: "uncategorized",
            confidence = node.path("confidence").textOrNull() ?: "LOW",
            status = node.path("status").textOrNull() ?: "DRAFT",
            occurredAt = node.path("occurredAt").textOrNull() ?: "",
            notes = node.path("notes").textOrNull() ?: ""
        )
    }

    private fun parseDraft(node: JsonNode): Draft {
        return Draft(
            confidence = node.path("confidence").textOrNull() ?: "LOW",
            needsReview = node.path("needsReview").let { it.isBoolean && it.asBoolean() },
            amount = node.path("amount").textOrNull() ?: "0",
            currency = node.path("currency").textOrNull() ?: "",
            merchant = node.path("merchant").textOrNull() ?: "",
            category = node.path("category").textOrNull() ?: "uncategorized",
            cardMask = node.path("cardMask").textOrNull() ?: "",
            occurredAt = node.path("occurredAt").textOrNull() ?: "",
            rawMasked = node.path("rawMasked").textOrNull() ?: "",
            notes = node.path("notes").takeIf(JsonNode::isArray)?.mapNotNull { it.textOrNull() } ?: emptyList()
        )
    }

    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText(null)?.takeIf { it.isNotBlank() }
}
