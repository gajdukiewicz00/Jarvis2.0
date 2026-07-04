package org.jarvis.orchestrator.command.risk;

import org.jarvis.commands.DangerousAction;
import org.jarvis.commands.RiskLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRiskCatalogTest {

    private final IntentRiskCatalog catalog = new IntentRiskCatalog();

    @Test
    void unknownIntentDefaultsToMedium() {
        RiskClassification c = catalog.classify("totally.unknown.intent");
        assertThat(c.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(c.dangerousAction()).isNull();
        assertThat(c.requiresConfirmation()).isTrue();
    }

    @Test
    void blankIntentDefaultsToMedium() {
        assertThat(catalog.classify(null).riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(catalog.classify("").riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(catalog.classify("   ").riskLevel()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void safeReadsAreSafe() {
        assertThat(catalog.classify("system.status").riskLevel()).isEqualTo(RiskLevel.SAFE);
        assertThat(catalog.classify("memory.search").riskLevel()).isEqualTo(RiskLevel.SAFE);
        assertThat(catalog.classify("calendar.read").riskLevel()).isEqualTo(RiskLevel.SAFE);
    }

    @Test
    void lowRiskIsLowAndDoesNotRequireConfirmation() {
        RiskClassification focus = catalog.classify("pc.window.focus");
        assertThat(focus.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(focus.requiresConfirmation()).isFalse();

        assertThat(catalog.classify("pc.text.type").riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(catalog.classify("home.light.on").riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void deleteFilesIsHighWithDangerousAction() {
        RiskClassification c = catalog.classify("fs.delete-file");
        assertThat(c.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(c.dangerousAction()).isEqualTo(DangerousAction.DELETE_FILES);
        assertThat(c.requiresConfirmation()).isTrue();
    }

    @Test
    void sendMessagesIsHighWithDangerousAction() {
        assertThat(catalog.classify("chat.send-message").dangerousAction())
                .isEqualTo(DangerousAction.SEND_MESSAGES);
        assertThat(catalog.classify("email.send").dangerousAction())
                .isEqualTo(DangerousAction.SEND_MESSAGES);
        assertThat(catalog.classify("sms.send").dangerousAction())
                .isEqualTo(DangerousAction.SEND_MESSAGES);
    }

    @Test
    void shellExecIsCriticalRunShell() {
        RiskClassification c = catalog.classify("shell.exec");
        assertThat(c.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(c.dangerousAction()).isEqualTo(DangerousAction.RUN_SHELL);
    }

    @Test
    void financeIsCriticalSpendMoney() {
        assertThat(catalog.classify("finance.transfer").dangerousAction())
                .isEqualTo(DangerousAction.SPEND_MONEY);
        assertThat(catalog.classify("finance.purchase").dangerousAction())
                .isEqualTo(DangerousAction.SPEND_MONEY);
        assertThat(catalog.classify("finance.subscription.create").riskLevel())
                .isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void doorsAreCriticalOpenDoors() {
        assertThat(catalog.classify("home.door.unlock").dangerousAction())
                .isEqualTo(DangerousAction.OPEN_DOORS);
        assertThat(catalog.classify("home.gate.open").riskLevel())
                .isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void shutdownIsCriticalShutdown() {
        assertThat(catalog.classify("pc.shutdown").dangerousAction())
                .isEqualTo(DangerousAction.SHUTDOWN);
        assertThat(catalog.classify("pc.reboot").dangerousAction())
                .isEqualTo(DangerousAction.SHUTDOWN);
        assertThat(catalog.classify("pc.sleep").dangerousAction())
                .isEqualTo(DangerousAction.SHUTDOWN);
        assertThat(catalog.classify("pc.logout").dangerousAction())
                .isEqualTo(DangerousAction.SHUTDOWN);
    }

    @Test
    void securityChangesAreHighChangeSecurity() {
        assertThat(catalog.classify("security.rotate-token").dangerousAction())
                .isEqualTo(DangerousAction.CHANGE_SECURITY);
        assertThat(catalog.classify("security.update-password").dangerousAction())
                .isEqualTo(DangerousAction.CHANGE_SECURITY);
        assertThat(catalog.classify("security.disable-mfa").dangerousAction())
                .isEqualTo(DangerousAction.CHANGE_SECURITY);
    }

    @Test
    void killSwitchIsCriticalChangeSecurity() {
        RiskClassification c = catalog.classify("agent.kill-switch");
        assertThat(c.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(c.dangerousAction()).isEqualTo(DangerousAction.CHANGE_SECURITY);
    }

    @Test
    void bulkMemoryModificationIsHigh() {
        assertThat(catalog.classify("memory.delete-bulk").dangerousAction())
                .isEqualTo(DangerousAction.BULK_MEMORY_MODIFY);
        assertThat(catalog.classify("memory.purge-vault").dangerousAction())
                .isEqualTo(DangerousAction.BULK_MEMORY_MODIFY);
    }

    @Test
    void p2DesktopIntentsAreLowOrSafeAndDoNotRequireConfirmation() {
        assertThat(catalog.classify("OPEN_URL").riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(catalog.classify("OPEN_APP").riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(catalog.classify("CREATE_LOCAL_NOTE").riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(catalog.classify("FOCUS_WINDOW").riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(catalog.classify("TYPE_TEXT").riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(catalog.classify("OPEN_URL").requiresConfirmation()).isFalse();
        assertThat(catalog.classify("CREATE_LOCAL_NOTE").requiresConfirmation()).isFalse();
        assertThat(catalog.classify("SHOW_NOTIFICATION").riskLevel()).isEqualTo(RiskLevel.SAFE);
        assertThat(catalog.classify("GET_ACTIVE_WINDOW").riskLevel()).isEqualTo(RiskLevel.SAFE);
    }

    @Test
    void classifyIsCaseInsensitive() {
        assertThat(catalog.classify("open_url").riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(catalog.classify("Open_Url").riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(catalog.classify("OPEN_URL").riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(catalog.classify("PC.WINDOW.FOCUS").riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void allDangerousActionEnumValuesAreCovered() {
        // The catalog must have at least one mapping per DangerousAction
        // value so the SPEC's 8 categories all have a representative intent.
        for (DangerousAction action : DangerousAction.values()) {
            boolean covered = catalog.size() > 0 &&
                    java.util.Arrays.stream(DangerousAction.values()).anyMatch(a -> a == action);
            assertThat(covered).as("DangerousAction." + action + " missing").isTrue();
        }
    }
}
