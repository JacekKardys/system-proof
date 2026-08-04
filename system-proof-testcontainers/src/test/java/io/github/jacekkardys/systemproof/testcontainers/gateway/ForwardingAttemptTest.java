package io.github.jacekkardys.systemproof.testcontainers.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ForwardingAttemptTest {
    @Test
    void shouldReportSuccessOnlyAfterOneWriteAndFlush() throws Exception {
        RecordingOutput output = new RecordingOutput(false);
        RecordingReporter reporter = new RecordingReporter();

        ForwardingAttempt.writeAndFlush(output, new byte[] {1, 2, 3}, reporter);

        assertThat(output.writeCalls).hasValue(1);
        assertThat(output.flushCalls).hasValue(1);
        assertThat(reporter.forwardedCalls).hasValue(1);
        assertThat(reporter.writeFailureCalls).hasValue(0);
    }

    @Test
    void shouldReportFlushFailureWithoutRetryOrFalseForwardedResult() {
        RecordingOutput output = new RecordingOutput(true);
        RecordingReporter reporter = new RecordingReporter();

        assertThatThrownBy(() -> ForwardingAttempt.writeAndFlush(
            output,
            new byte[] {1, 2, 3},
            reporter
        )).isInstanceOf(IOException.class)
            .hasMessage("controlled flush failure");

        assertThat(output.writeCalls).hasValue(1);
        assertThat(output.flushCalls).hasValue(1);
        assertThat(reporter.forwardedCalls).hasValue(0);
        assertThat(reporter.writeFailureCalls).hasValue(1);
    }

    @Test
    void shouldReportUncheckedWriteFailureExactlyOnceWithoutFlushOrRetry() {
        RuntimeFailureOutput output = new RuntimeFailureOutput(FailurePoint.WRITE);
        RecordingReporter reporter = new RecordingReporter();

        assertThatThrownBy(() -> ForwardingAttempt.writeAndFlush(
            output,
            new byte[] {1, 2, 3},
            reporter
        )).isSameAs(output.failure);

        assertThat(output.writeCalls).hasValue(1);
        assertThat(output.flushCalls).hasValue(0);
        assertThat(reporter.forwardedCalls).hasValue(0);
        assertThat(reporter.writeFailureCalls).hasValue(1);
    }

    @Test
    void shouldReportUncheckedFlushFailureExactlyOnceWithoutRetry() {
        RuntimeFailureOutput output = new RuntimeFailureOutput(FailurePoint.FLUSH);
        RecordingReporter reporter = new RecordingReporter();

        assertThatThrownBy(() -> ForwardingAttempt.writeAndFlush(
            output,
            new byte[] {1, 2, 3},
            reporter
        )).isSameAs(output.failure);

        assertThat(output.writeCalls).hasValue(1);
        assertThat(output.flushCalls).hasValue(1);
        assertThat(reporter.forwardedCalls).hasValue(0);
        assertThat(reporter.writeFailureCalls).hasValue(1);
    }

    private static final class RecordingOutput extends OutputStream {
        private final boolean failFlush;
        private final AtomicInteger writeCalls = new AtomicInteger();
        private final AtomicInteger flushCalls = new AtomicInteger();

        private RecordingOutput(boolean failFlush) {
            this.failFlush = failFlush;
        }

        @Override
        public void write(int value) {
            throw new AssertionError("Bulk write was expected");
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            writeCalls.incrementAndGet();
        }

        @Override
        public void flush() throws IOException {
            flushCalls.incrementAndGet();
            if (failFlush) {
                throw new IOException("controlled flush failure");
            }
        }
    }

    private static final class RecordingReporter
        implements ForwardingAttempt.ResultReporter {
        private final AtomicInteger forwardedCalls = new AtomicInteger();
        private final AtomicInteger writeFailureCalls = new AtomicInteger();

        @Override
        public void forwarded() {
            forwardedCalls.incrementAndGet();
        }

        @Override
        public void writeFailed() {
            writeFailureCalls.incrementAndGet();
        }
    }

    private enum FailurePoint {
        WRITE,
        FLUSH
    }

    private static final class RuntimeFailureOutput extends OutputStream {
        private final FailurePoint failurePoint;
        private final IllegalStateException failure =
            new IllegalStateException("controlled unchecked output failure");
        private final AtomicInteger writeCalls = new AtomicInteger();
        private final AtomicInteger flushCalls = new AtomicInteger();

        private RuntimeFailureOutput(FailurePoint failurePoint) {
            this.failurePoint = failurePoint;
        }

        @Override
        public void write(int value) {
            throw new AssertionError("Bulk write was expected");
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            writeCalls.incrementAndGet();
            if (failurePoint == FailurePoint.WRITE) {
                throw failure;
            }
        }

        @Override
        public void flush() {
            flushCalls.incrementAndGet();
            if (failurePoint == FailurePoint.FLUSH) {
                throw failure;
            }
        }
    }
}
