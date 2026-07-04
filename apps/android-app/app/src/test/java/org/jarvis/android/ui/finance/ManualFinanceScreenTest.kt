package org.jarvis.android.ui.finance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [buildFinanceEntryPayload] — extracted from the Save button's onClick
 * lambda in [ManualFinanceScreen] so amount-parsing/JSON-building can be tested without
 * Compose/instrumentation.
 */
class ManualFinanceScreenTest {

    @Test
    fun buildFinanceEntryPayload_returnsNullForNonNumericAmount() {
        val payload = buildFinanceEntryPayload("not-a-number", "EUR", "groceries", "milk")

        assertNull(payload)
    }

    @Test
    fun buildFinanceEntryPayload_returnsNullForBlankAmount() {
        val payload = buildFinanceEntryPayload("", "EUR", "groceries", "milk")

        assertNull(payload)
    }

    @Test
    fun buildFinanceEntryPayload_buildsExpectedJsonForValidAmount() {
        val payloadJson = buildFinanceEntryPayload("42.5", "EUR", "groceries", "milk")

        requireNotNull(payloadJson)
        val json = Json.parseToJsonElement(payloadJson).jsonObject
        assertEquals(42.5, json["amount"]!!.jsonPrimitive.double, 0.0001)
        assertEquals("EUR", json["currency"]!!.jsonPrimitive.content)
        assertEquals("groceries", json["category"]!!.jsonPrimitive.content)
        assertEquals("milk", json["description"]!!.jsonPrimitive.content)
        assertEquals("EXPENSE", json["type"]!!.jsonPrimitive.content)
    }
}
