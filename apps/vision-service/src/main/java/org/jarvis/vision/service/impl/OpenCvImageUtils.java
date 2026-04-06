package org.jarvis.vision.service.impl;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class OpenCvImageUtils {

    private OpenCvImageUtils() {
    }

    public static Mat toMat(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(copyToRgb(image), "png", output);
            return Imgcodecs.imdecode(new MatOfByte(output.toByteArray()), Imgcodecs.IMREAD_COLOR);
        }
    }

    public static BufferedImage fromMat(Mat mat) throws IOException {
        MatOfByte encoded = new MatOfByte();
        Imgcodecs.imencode(".png", mat, encoded);
        return decode(encoded.toArray());
    }

    public static byte[] encodePng(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(copyToRgb(image), "png", output);
            return output.toByteArray();
        }
    }

    public static BufferedImage crop(BufferedImage image, int x, int y, int width, int height) {
        int boundedX = Math.max(0, x);
        int boundedY = Math.max(0, y);
        int boundedWidth = Math.min(width, image.getWidth() - boundedX);
        int boundedHeight = Math.min(height, image.getHeight() - boundedY);
        return image.getSubimage(boundedX, boundedY, boundedWidth, boundedHeight);
    }

    public static BufferedImage centerCropSquare(BufferedImage image) {
        int side = Math.min(image.getWidth(), image.getHeight());
        int startX = Math.max(0, (image.getWidth() - side) / 2);
        int startY = Math.max(0, (image.getHeight() - side) / 2);
        return copy(crop(image, startX, startY, side, side));
    }

    public static BufferedImage resize(BufferedImage image, int width, int height, int imageType) {
        BufferedImage resized = new BufferedImage(width, height, imageType);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }

    public static BufferedImage copy(BufferedImage image) {
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = copy.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }

    public static BufferedImage decode(byte[] imageBytes) throws IOException {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(imageBytes)) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                throw new IOException("Unsupported image payload");
            }
            return image;
        }
    }

    private static BufferedImage copyToRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_3BYTE_BGR || source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage converted = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = converted.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return converted;
    }
}
