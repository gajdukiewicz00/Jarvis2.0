package org.jarvis.cloudrelay;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 12 — structural proof that the cloud-relay cannot read user
 * data. SPEC-1 §"Cloud relay only for discovery/relay, never readable
 * personal data" is enforced by the dependency graph: the relay module
 * does NOT pull {@code sync-protocol}, {@code event-schema}, or any
 * Jarvis decryption helper, so even a malicious build of the relay
 * lacks the symbols required to deserialize a {@code SyncEnvelope} or
 * derive a session key.
 *
 * <p>This test fails the build the moment someone tries to add such a
 * dependency.</p>
 */
class RelayCannotReadPayloadTest {

    private static final Set<String> FORBIDDEN_PACKAGE_PREFIXES = Set.of(
            "org/jarvis/sync/",            // sync-protocol envelope + crypto
            "org/jarvis/sync/crypto/",
            "org/jarvis/events/",          // audit event types — no leakage of who logged what
            "org/jarvis/common/eventbus/", // AuditPublisher
            "org/jarvis/lifetracker/",     // any life-tracker domain
            "org/jarvis/orchestrator/",
            "org/jarvis/memory/",
            "org/jarvis/visionsecurity/",
            "org/jarvis/planner/"
    );

    @Test
    void relayClasspathExcludesAnyJarvisDecryptionOrPersonalDataPackage() throws IOException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Set<String> seen = new HashSet<>();
        for (String pkg : FORBIDDEN_PACKAGE_PREFIXES) {
            Enumeration<URL> resources = cl.getResources(pkg);
            while (resources.hasMoreElements()) {
                seen.add(pkg + " <- " + resources.nextElement());
            }
        }
        assertThat(seen)
                .as("cloud-relay must not have access to Jarvis decryption / personal-data classes; "
                        + "found resources matching forbidden packages: %s", seen)
                .isEmpty();
    }
}
