package org.jarvis.visionsecurity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnrollmentStoreCacheTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void loadOpenCv() {
        Loader.load(opencv_java.class);
    }

    @Test
    void consecutiveLoadsReturnIndependentMatInstances() throws Exception {
        VisionSecurityProperties properties = new VisionSecurityProperties();
        properties.getStorage().setRoot(tempDir.toString());
        EnrollmentStore store = new EnrollmentStore(properties, new ObjectMapper().findAndRegisterModules());

        List<Mat> samples = buildSamples(160, 4);
        try {
            store.saveEnrollment("owner", samples, 70.0, 100.0);
        } finally {
            samples.forEach(Mat::release);
        }

        List<Mat> first = store.loadSamples("owner");
        List<Mat> second = store.loadSamples("owner");
        try {
            assertThat(first).hasSize(4);
            assertThat(second).hasSize(4);
            for (int i = 0; i < first.size(); i++) {
                assertThat(first.get(i).getNativeObjAddr())
                        .as("loadSamples must hand out independent Mats so callers can safely release()")
                        .isNotEqualTo(second.get(i).getNativeObjAddr());
                assertThat(first.get(i).size()).isEqualTo(second.get(i).size());
            }
        } finally {
            first.forEach(Mat::release);
            second.forEach(Mat::release);
        }
    }

    @Test
    void cacheRefreshesWhenSamplesOnDiskChange() throws Exception {
        VisionSecurityProperties properties = new VisionSecurityProperties();
        properties.getStorage().setRoot(tempDir.toString());
        EnrollmentStore store = new EnrollmentStore(properties, new ObjectMapper().findAndRegisterModules());

        List<Mat> samples = buildSamples(160, 4);
        try {
            store.saveEnrollment("owner", samples, 70.0, 100.0);
        } finally {
            samples.forEach(Mat::release);
        }

        List<Mat> first = store.loadSamples("owner");
        first.forEach(Mat::release);

        Path samplesDir = store.samplesDirectory("owner");
        java.nio.file.Files.delete(samplesDir.resolve("sample-01.png"));

        List<Mat> second = store.loadSamples("owner");
        try {
            assertThat(second).hasSize(3);
        } finally {
            second.forEach(Mat::release);
        }
    }

    @Test
    void cacheInvalidatesOnReset() throws Exception {
        VisionSecurityProperties properties = new VisionSecurityProperties();
        properties.getStorage().setRoot(tempDir.toString());
        EnrollmentStore store = new EnrollmentStore(properties, new ObjectMapper().findAndRegisterModules());

        List<Mat> samples = buildSamples(160, 3);
        try {
            store.saveEnrollment("owner", samples, 70.0, 100.0);
        } finally {
            samples.forEach(Mat::release);
        }

        List<Mat> firstLoad = store.loadSamples("owner");
        firstLoad.forEach(Mat::release);
        assertThat(store.isEnrolled("owner")).isTrue();

        store.reset("owner");
        assertThat(store.isEnrolled("owner")).isFalse();

        List<Mat> afterReset = store.loadSamples("owner");
        try {
            assertThat(afterReset).isEmpty();
        } finally {
            afterReset.forEach(Mat::release);
        }
    }

    @Test
    void cacheInvalidatesOnNewEnrollment() throws Exception {
        VisionSecurityProperties properties = new VisionSecurityProperties();
        properties.getStorage().setRoot(tempDir.toString());
        EnrollmentStore store = new EnrollmentStore(properties, new ObjectMapper().findAndRegisterModules());

        List<Mat> firstBatch = buildSamples(160, 3);
        try {
            store.saveEnrollment("owner", firstBatch, 70.0, 100.0);
        } finally {
            firstBatch.forEach(Mat::release);
        }
        List<Mat> first = store.loadSamples("owner");
        first.forEach(Mat::release);

        List<Mat> secondBatch = buildSamples(160, 6);
        try {
            store.saveEnrollment("owner", secondBatch, 65.0, 95.0);
        } finally {
            secondBatch.forEach(Mat::release);
        }

        List<Mat> second = store.loadSamples("owner");
        try {
            assertThat(second).hasSize(6);
        } finally {
            second.forEach(Mat::release);
        }
    }

    private List<Mat> buildSamples(int size, int count) {
        List<Mat> samples = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Mat mat = new Mat(size, size, CvType.CV_8UC1, new Scalar(40 + i * 30));
            samples.add(mat);
        }
        return samples;
    }
}
