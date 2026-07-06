package org.jarvis.syncservice.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyncServicePropertiesTest {

    @Test
    void defaultsMatchApplicationYmlBaseline() {
        SyncServiceProperties props = new SyncServiceProperties();

        assertThat(props.getServerKeyPath()).isEqualTo("/var/lib/jarvis/sync/server-keys.json");
        assertThat(props.getReplayCacheSizePerDevice()).isEqualTo(4096);
        assertThat(props.getPairingNonceTtlSeconds()).isEqualTo(120L);
        assertThat(props.getLifeTrackerUrl()).isEqualTo("http://life-tracker:8085");
        assertThat(props.getOrchestratorUrl()).isEqualTo("http://orchestrator:8080");
        assertThat(props.getDispatchTimeoutMillis()).isEqualTo(1500L);
        assertThat(props.getRecordsDeltaDefaultPageSize()).isEqualTo(100);
        assertThat(props.getRecordsDeltaMaxPageSize()).isEqualTo(500);
        assertThat(props.getRecordsConflictLogCapacity()).isEqualTo(1000);
    }

    @Test
    void settersOverrideEveryField() {
        SyncServiceProperties props = new SyncServiceProperties();

        props.setServerKeyPath("/tmp/keys.json");
        props.setReplayCacheSizePerDevice(10);
        props.setPairingNonceTtlSeconds(30L);
        props.setLifeTrackerUrl("http://life-tracker-test:9000");
        props.setOrchestratorUrl("http://orchestrator-test:9001");
        props.setDispatchTimeoutMillis(250L);
        props.setRecordsDeltaDefaultPageSize(20);
        props.setRecordsDeltaMaxPageSize(40);
        props.setRecordsConflictLogCapacity(50);

        assertThat(props.getServerKeyPath()).isEqualTo("/tmp/keys.json");
        assertThat(props.getReplayCacheSizePerDevice()).isEqualTo(10);
        assertThat(props.getPairingNonceTtlSeconds()).isEqualTo(30L);
        assertThat(props.getLifeTrackerUrl()).isEqualTo("http://life-tracker-test:9000");
        assertThat(props.getOrchestratorUrl()).isEqualTo("http://orchestrator-test:9001");
        assertThat(props.getDispatchTimeoutMillis()).isEqualTo(250L);
        assertThat(props.getRecordsDeltaDefaultPageSize()).isEqualTo(20);
        assertThat(props.getRecordsDeltaMaxPageSize()).isEqualTo(40);
        assertThat(props.getRecordsConflictLogCapacity()).isEqualTo(50);
    }
}
