package org.jarvis.desktop.features.finance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient

/**
 * Read model for the Finance draft-review inbox.
 *
 * life-tracker's `BankNotificationParser` only auto-stores HIGH-confidence,
 * valid drafts (US-BANK-005); LOW/MEDIUM-confidence drafts are returned with
 * `needsReview=true` instead of being persisted, so there is no server-side
 * "review inbox" table to page through yet. This model surfaces that same
 * signal the way it is actually available today:
 *  - batch-parse raw notifications (one per line) -> POST /api/v1/life/finance/import-csv-notifications,
 *    returning the `needsReview` drafts as the review queue
 *  - approve a draft (persist it as a real expense) -> POST /api/v1/life/finance/expenses
 *
 * Reject and edit are handled entirely client-side by [FinanceReviewView]:
 * reject just drops the draft from the in-memory queue (it was never
 * persisted), and edit produces a modified copy that gets approved in its
 * place — there is nothing to revoke server-side either way.
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
