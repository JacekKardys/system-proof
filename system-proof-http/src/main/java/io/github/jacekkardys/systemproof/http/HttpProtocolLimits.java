package io.github.jacekkardys.systemproof.http;

/** HTTP-specific limits applied within the gateway's frame and aggregate-buffer limits. */
public record HttpProtocolLimits(
    int maximumStartLineBytes,
    int maximumHeaderSectionBytes,
    int maximumHeaderCount,
    int maximumBodyBytes
) {
    public static final int MAXIMUM_START_LINE_BYTES = 16 * 1024;
    public static final int MAXIMUM_HEADER_SECTION_BYTES = 64 * 1024;
    public static final int MAXIMUM_HEADER_COUNT = 1024;
    public static final int MAXIMUM_BODY_BYTES = 16 * 1024 * 1024;

    public HttpProtocolLimits {
        if (maximumStartLineBytes < 1
            || maximumStartLineBytes > MAXIMUM_START_LINE_BYTES) {
            throw new IllegalArgumentException(
                "maximumStartLineBytes must be between 1 and " + MAXIMUM_START_LINE_BYTES
            );
        }
        if ((long) maximumHeaderSectionBytes < (long) maximumStartLineBytes + 4
            || maximumHeaderSectionBytes > MAXIMUM_HEADER_SECTION_BYTES) {
            throw new IllegalArgumentException(
                "maximumHeaderSectionBytes must accommodate the start line and not exceed "
                    + MAXIMUM_HEADER_SECTION_BYTES
            );
        }
        if (maximumHeaderCount < 1 || maximumHeaderCount > MAXIMUM_HEADER_COUNT) {
            throw new IllegalArgumentException(
                "maximumHeaderCount must be between 1 and " + MAXIMUM_HEADER_COUNT
            );
        }
        if (maximumBodyBytes < 0 || maximumBodyBytes > MAXIMUM_BODY_BYTES) {
            throw new IllegalArgumentException(
                "maximumBodyBytes must be between 0 and " + MAXIMUM_BODY_BYTES
            );
        }
    }

    /** Returns the bounded defaults used by the characterized reference adapter. */
    public static HttpProtocolLimits defaults() {
        return new HttpProtocolLimits(8 * 1024, 32 * 1024, 100, 1024 * 1024);
    }
}
