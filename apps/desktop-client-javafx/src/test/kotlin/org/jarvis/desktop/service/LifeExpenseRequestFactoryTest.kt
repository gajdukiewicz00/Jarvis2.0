package org.jarvis.desktop.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class LifeExpenseRequestFactoryTest {

    private val json = Json.parseToJsonElement(
        LifeExpenseRequestFactory().create(
            amount = BigDecimal("12.50"),
            currency = "EUR",
            category = "Food",
            description = "Lunch",
            userId = "42"
        )
    ).jsonObject

    @Test
    @DisplayName("create includes the authenticated user id when available")
    fun createIncludesAuthenticatedUserId() {
        assertEquals("42", json["userId"]?.jsonPrimitive?.content)
        assertEquals("Lunch", json["description"]?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("create omits userId when no authenticated user is available")
    fun createOmitsUserIdWhenUserIsUnknown() {
        val payload = Json.parseToJsonElement(
            LifeExpenseRequestFactory().create(
                amount = BigDecimal("12.50"),
                currency = "EUR",
                category = "Food",
                description = null,
                userId = null
            )
        ).jsonObject

        assertFalse(payload.containsKey("userId"))
    }
}
