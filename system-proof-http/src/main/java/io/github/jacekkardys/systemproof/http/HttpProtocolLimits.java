package io.github.jacekkardys.systemproof.http;

/** HTTP-specific limits applied within the gateway's frame and aggregate-buffer limits. */
public record HttpProtocolLimits(
    int maximumStartLineBytes,
    int maximumHeaderSectionBytes,
    int maximumHeaderCount,
    int maximumBodyBytes
) {
    public HttpProtocolLimits {
        if (maximumStartLineBytes < 1) {
            throw new IllegalArgumentException("maximumStartLineBytes must be positive");
        }
        if (maximumHeaderSectionBytes < maximumStartLineBytes + 4) {
            throw new IllegalArgumentException(
                "maximumHeaderSectionBytes must accommodate the start line"
            );
        }
        if (maximumHeaderCount < 1) {
            throw new IllegalArgumentException("maximumHeaderCount must be positive");
        }
        if (maximumBodyBytes < 0) {
            throw new IllegalArgumentException("maximumBodyBytes must not be negative");
        }
    }

    /** Returns the bounded defaults used by the characterized reference adapter. */
    public static HttpProtocolLimits defaults() {
        return new HttpProtocolLimits(8 * 1024, 32 * 1024, 100, 1024 * 1024);
    }
}
