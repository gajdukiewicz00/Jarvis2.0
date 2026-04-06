package org.jarvis.vision.service.impl;

import lombok.RequiredArgsConstructor;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.FaceEmbeddingEncoder;
import org.springframework.stereotype.Component;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

@Component("blockGradientFaceEmbeddingEncoder")
@RequiredArgsConstructor
public class BlockGradientFaceEmbeddingEncoder implements FaceEmbeddingEncoder {

    private static final String ENCODER = "block-gradient-mvp";

    private final VisionServiceProperties properties;

    @Override
    public String encoderId() {
        return ENCODER;
    }

    @Override
    public String cacheKey() {
        return encoderId()
                + "::faceImageSize=" + properties.getEmbedding().getFaceImageSize()
                + "::poolingGridSize=" + properties.getEmbedding().getPoolingGridSize();
    }

    @Override
    public double[] encode(BufferedImage faceImage) {
        int imageSize = properties.getEmbedding().getFaceImageSize();
        int gridSize = properties.getEmbedding().getPoolingGridSize();
        BufferedImage normalized = normalize(faceImage, imageSize);
        double[][] grayscale = toGrayMatrix(normalized);
        int cellSize = Math.max(1, imageSize / gridSize);
        int cellCount = gridSize * gridSize;

        double[] pooledIntensity = new double[cellCount];
        double[] pooledGradX = new double[cellCount];
        double[] pooledGradY = new double[cellCount];

        int index = 0;
        for (int cellY = 0; cellY < gridSize; cellY++) {
            for (int cellX = 0; cellX < gridSize; cellX++) {
                int startX = cellX * cellSize;
                int startY = cellY * cellSize;
                int endX = Math.min(imageSize, startX + cellSize);
                int endY = Math.min(imageSize, startY + cellSize);

                double intensitySum = 0.0d;
                double gradXSum = 0.0d;
                double gradYSum = 0.0d;
                int samples = 0;
                for (int y = startY; y < endY; y++) {
                    for (int x = startX; x < endX; x++) {
                        double value = grayscale[y][x];
                        double gradX = x == 0 ? 0.0d : value - grayscale[y][x - 1];
                        double gradY = y == 0 ? 0.0d : value - grayscale[y - 1][x];
                        intensitySum += value;
                        gradXSum += Math.abs(gradX);
                        gradYSum += Math.abs(gradY);
                        samples++;
                    }
                }

                pooledIntensity[index] = samples == 0 ? 0.0d : intensitySum / samples;
                pooledGradX[index] = samples == 0 ? 0.0d : gradXSum / samples;
                pooledGradY[index] = samples == 0 ? 0.0d : gradYSum / samples;
                index++;
            }
        }

        double meanIntensity = java.util.Arrays.stream(pooledIntensity).average().orElse(0.0d);
        for (int i = 0; i < pooledIntensity.length; i++) {
            pooledIntensity[i] -= meanIntensity;
        }

        double[] embedding = new double[cellCount * 3];
        System.arraycopy(pooledIntensity, 0, embedding, 0, cellCount);
        System.arraycopy(pooledGradX, 0, embedding, cellCount, cellCount);
        System.arraycopy(pooledGradY, 0, embedding, cellCount * 2, cellCount);
        return normalizeVector(embedding);
    }

    @Override
    public Map<String, String> statusDetails() {
        Map<String, String> details = new LinkedHashMap<>(FaceEmbeddingEncoder.super.statusDetails());
        details.put("embeddingModelConfigured", "false");
        details.put("embeddingFaceImageSize", String.valueOf(properties.getEmbedding().getFaceImageSize()));
        details.put("embeddingPoolingGridSize", String.valueOf(properties.getEmbedding().getPoolingGridSize()));
        return details;
    }

    private static BufferedImage normalize(BufferedImage source, int imageSize) {
        BufferedImage grayscale = new BufferedImage(imageSize, imageSize, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = grayscale.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, imageSize, imageSize, null);
        } finally {
            graphics.dispose();
        }
        return grayscale;
    }

    private static double[][] toGrayMatrix(BufferedImage image) {
        double[][] matrix = new double[image.getHeight()][image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                matrix[y][x] = (red * 0.299d + green * 0.587d + blue * 0.114d) / 255.0d;
            }
        }
        return matrix;
    }

    private static double[] normalizeVector(double[] vector) {
        double norm = 0.0d;
        for (double value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0d) {
            return vector;
        }
        double[] normalized = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] / norm;
        }
        return normalized;
    }
}
