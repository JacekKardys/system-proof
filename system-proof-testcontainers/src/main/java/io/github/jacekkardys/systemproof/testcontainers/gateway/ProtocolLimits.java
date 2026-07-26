package io.github.jacekkardys.systemproof.testcontainers.gateway;

/** Hard byte limits applied independently to each physical session direction. */
public record ProtocolLimits(int maximumFrameBytes, int maximumBufferedBytes) {
    public ProtocolLimits {
        if (maximumFrameBytes < 1) {
            throw new IllegalArgumentException("maximumFrameBytes must be positive");
        }
        if (maximumBufferedBytes < maximumFrameBytes) {
            throw new IllegalArgumentException(
                "maximumBufferedBytes must be at least maximumFrameBytes"
            );
        }
    }
}
