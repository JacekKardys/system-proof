package io.github.jacekkardys.systemproof.testcontainers.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/** Performs one non-retriable original-byte write/flush attempt and reports its result. */
final class ForwardingAttempt {
    private ForwardingAttempt() {}

    static void writeAndFlush(
        OutputStream destination,
        byte[] originalBytes,
        ResultReporter reporter
    ) throws IOException {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(originalBytes, "originalBytes must not be null");
        reporter = Objects.requireNonNull(reporter, "reporter must not be null");
        try {
            destination.write(originalBytes);
            destination.flush();
        } catch (IOException writeFailure) {
            try {
                reporter.writeFailed();
            } catch (RuntimeException | Error callbackFailure) {
                writeFailure.addSuppressed(callbackFailure);
            }
            throw writeFailure;
        }
        reporter.forwarded();
    }

    interface ResultReporter {
        void forwarded();

        void writeFailed();
    }
}
