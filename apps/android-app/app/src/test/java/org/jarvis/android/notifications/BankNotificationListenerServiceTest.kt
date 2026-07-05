package org.jarvis.android.notifications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [buildBankDraftPayload] — extracted from
 * [BankNotificationListenerService.handlePosted] so the FINANCE_ENTRY draft JSON shape
 * can be tested without a live `StatusBarNotification`/Android runtime.
 */
class BankNotificationListenerServiceTest {

    @Test
    fun buildBankDraftPayload_carriesSanitizedFieldsWhenRawTextStored() {
        val payloadJson = buildBankDraftPayload(
            packageName = "pl.mbank",
            bankName = "mBank",
            sanitizedTitle = "mBank",
            sanitizedText = "Card payment of 42.50 PLN",
            postedAtEpochMs = 1_000L,
            storeRawText = true
        )

        val json = Json.parseToJsonElement(payloadJson).jsonObject
        assertEquals("DRAFT", json["type"]!!.jsonPrimitive.content)
        assertEquals("BANK_NOTIFICATION", json["source"]!!.jsonPrimitive.content)
        assertEquals("pl.mbank", json["bankPackage"]!!.jsonPrimitive.content)
        assertEquals("mBank", json["bankName"]!!.jsonPrimitive.content)
        assertEquals("mBank", json["title"]!!.jsonPrimitive.content)
        assertEquals("Card payment of 42.50 PLN", json["description"]!!.jsonPrimitive.content)
        assertTrue(json["rawTextStored"]!!.jsonPrimitive.boolean)
        assertTrue(json["needsReview"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun buildBankDraftPayload_omitsRawTextWhenStoreRawTextIsFalse() {
        val payloadJson = buildBankDraftPayload(
            packageName = "pl.mbank",
            bankName = "mBank",
            sanitizedTitle = "mBank",
            sanitizedText = "Card payment of 42.50 PLN",
            postedAtEpochMs = 1_000L,
            storeRawText = false
        )

        val json = Json.parseToJsonElement(payloadJson).jsonObject
        assertEquals("", json["title"]!!.jsonPrimitive.content)
        assertEquals("", json["description"]!!.jsonPrimitive.content)
        assertEquals(false, json["rawTextStored"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun buildBankDraftPayload_handlesNullTitleAndTextGracefully() {
        val payloadJson = buildBankDraftPayload(
            packageName = "com.revolut.revolut",
            bankName = "Revolut",
            sanitizedTitle = null,
            sanitizedText = null,
            postedAtEpochMs = 2_000L,
            storeRawText = true
        )

        val json = Json.parseToJsonElement(payloadJson).jsonObject
        assertEquals("", json["title"]!!.jsonPrimitive.content)
        assertEquals("", json["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun buildBankDraftPayload_alwaysMarksNeedsReviewTrue() {
        val payloadJson = buildBankDraftPayload(
            packageName = "pl.mbank",
            bankName = "mBank",
            sanitizedTitle = null,
            sanitizedText = null,
            postedAtEpochMs = 0L,
            storeRawText = false
        )

        val json = Json.parseToJsonElement(payloadJson).jsonObject
        assertTrue(json["needsReview"]!!.jsonPrimitive.boolean)
    }
}
