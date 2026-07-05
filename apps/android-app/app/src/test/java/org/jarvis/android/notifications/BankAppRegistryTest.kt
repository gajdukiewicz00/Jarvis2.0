package org.jarvis.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BankAppRegistryTest {

    @Test
    fun displayNameFor_returnsFriendlyNameForKnownPackage() {
        assertEquals("mBank", BankAppRegistry.displayNameFor("pl.mbank"))
    }

    @Test
    fun displayNameFor_returnsRevolutForItsPackage() {
        assertEquals("Revolut", BankAppRegistry.displayNameFor("com.revolut.revolut"))
    }

    @Test
    fun displayNameFor_fallsBackToPackageNameWhenUnknown() {
        assertEquals("com.some.unknownbank", BankAppRegistry.displayNameFor("com.some.unknownbank"))
    }

    @Test
    fun defaultEnabledPackages_containsEveryKnownBankApp() {
        val defaults = BankAppRegistry.defaultEnabledPackages()

        BankAppRegistry.KNOWN_BANK_APPS.forEach { bank ->
            assertTrue("expected ${bank.packageName} in defaults", bank.packageName in defaults)
        }
        assertEquals(BankAppRegistry.KNOWN_BANK_APPS.size, defaults.size)
    }

    @Test
    fun knownBankApps_hasNoDuplicatePackageNames() {
        val packageNames = BankAppRegistry.KNOWN_BANK_APPS.map { it.packageName }

        assertEquals(packageNames.size, packageNames.toSet().size)
    }
}
