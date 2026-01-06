package org.jarvis.voicegateway.service;

public interface StreamingRecognitionSession extends AutoCloseable {
    /**
     * Process an audio chunk.
     * 
     * @param data   Audio data (16kHz mono PCM)
     * @param length Length of data
     * @return true if a final result is available (e.g. silence detected), false
     *         otherwise
     */
    boolean acceptWaveForm(byte[] data, int length);

    /**
     * Get the partial result (intermediate transcript).
     * 
     * @return JSON string or text
     */
    String getPartialResult();

    /**
     * Get the final result.
     * 
     * @return JSON string or text
     */
    String getResult();

    @Override
    void close();
}
