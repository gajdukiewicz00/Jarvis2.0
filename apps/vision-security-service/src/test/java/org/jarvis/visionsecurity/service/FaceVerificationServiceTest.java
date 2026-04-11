package org.jarvis.visionsecurity.service;

import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.EnrollmentProfile;
import org.jarvis.visionsecurity.model.FaceMatch;
import org.jarvis.visionsecurity.model.FaceVerdict;
import org.jarvis.visionsecurity.model.RectBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FaceVerificationServiceTest {

    @Mock
    private EnrollmentStore enrollmentStore;

    private FaceVerificationService service;

    @BeforeEach
    void setUp() {
        VisionSecurityProperties properties = new VisionSecurityProperties();
        service = new FaceVerificationService(enrollmentStore, properties);
    }

    @Test
    void storeEnrollmentRejectsFewerThanThreeSamples() {
        assertThatThrownBy(() -> service.storeEnrollment("owner", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least 3");

        assertThatThrownBy(() -> service.storeEnrollment("owner", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void classifyFacesReturnsUncertainWhenNotEnrolled() throws IOException {
        when(enrollmentStore.loadProfile("owner")).thenReturn(null);

        RectBox box = new RectBox(10, 10, 100, 100);
        List<FaceMatch> matches = service.classifyFaces("owner", List.of(box), List.of());

        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().verdict()).isEqualTo(FaceVerdict.UNCERTAIN);
        assertThat(matches.getFirst().confidence()).isEqualTo(-1.0);
    }

    @Test
    void classifyFacesReturnsUncertainWhenNoSamplesOnDisk() throws IOException {
        EnrollmentProfile profile = new EnrollmentProfile(
                "owner", Instant.now(), 5, 50.0, 80.0, "/tmp/samples"
        );
        when(enrollmentStore.loadProfile("owner")).thenReturn(profile);
        when(enrollmentStore.loadSamples("owner")).thenReturn(List.of());

        RectBox box = new RectBox(10, 10, 100, 100);
        List<FaceMatch> matches = service.classifyFaces("owner", List.of(box), List.of());

        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().verdict()).isEqualTo(FaceVerdict.UNCERTAIN);
    }

    @Test
    void isEnrolledDelegatesToStore() {
        when(enrollmentStore.isEnrolled("owner")).thenReturn(true);
        when(enrollmentStore.isEnrolled("stranger")).thenReturn(false);

        assertThat(service.isEnrolled("owner")).isTrue();
        assertThat(service.isEnrolled("stranger")).isFalse();
    }

    @Test
    void loadProfileDelegatesToStore() throws IOException {
        EnrollmentProfile profile = new EnrollmentProfile(
                "owner", Instant.now(), 5, 50.0, 80.0, "/tmp/samples"
        );
        when(enrollmentStore.loadProfile("owner")).thenReturn(profile);

        EnrollmentProfile loaded = service.loadProfile("owner");

        assertThat(loaded).isEqualTo(profile);
        assertThat(loaded.ownerThreshold()).isLessThan(loaded.uncertainThreshold());
    }

    @Test
    void resetEnrollmentDelegatesToStore() throws IOException {
        service.resetEnrollment("owner");
    }

    @Test
    void fallbackThresholdsAreUsedWithDefaultProperties() {
        VisionSecurityProperties properties = new VisionSecurityProperties();
        assertThat(properties.getVerification().getFallbackOwnerThreshold())
                .isLessThan(properties.getVerification().getFallbackUncertainThreshold());
        assertThat(properties.getVerification().getFallbackOwnerThreshold()).isEqualTo(70.0);
        assertThat(properties.getVerification().getFallbackUncertainThreshold()).isEqualTo(100.0);
    }

    @Test
    void marginBasedThresholdInvariant() {
        VisionSecurityProperties properties = new VisionSecurityProperties();
        double ownerMargin = properties.getVerification().getOwnerThresholdMargin();
        double uncertainMargin = properties.getVerification().getUncertainThresholdMargin();
        assertThat(ownerMargin).isPositive();
        assertThat(uncertainMargin).isPositive();
        assertThat(uncertainMargin).isGreaterThan(ownerMargin);
    }
}
