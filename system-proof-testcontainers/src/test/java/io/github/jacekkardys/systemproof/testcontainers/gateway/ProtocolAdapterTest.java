package io.github.jacekkardys.systemproof.testcontainers.gateway;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.engine.execution.CorrelationContribution;
import io.github.jacekkardys.systemproof.observation.FlowDirection;

class ProtocolAdapterTest {
    private static final ProtocolLimits LIMITS = new ProtocolLimits(128, 256);
    private final LengthPrefixedProtocolAdapter adapter =
        new LengthPrefixedProtocolAdapter();

    @Test
    void shouldDecodeByteFragmentationCoalescingAndEveryFrameSplit() throws Exception {
        byte[] first = LengthPrefixedProtocolAdapter.frame("alpha");
        byte[] second = LengthPrefixedProtocolAdapter.frame("beta");
        byte[] coalesced = concat(first, second);

        assertThat(decode(splitEveryByte(coalesced)))
            .containsExactly(first, second);
        assertThat(decode(List.of(coalesced)))
            .containsExactly(first, second);

        for (int split = 1; split < first.length; split++) {
            assertThat(decode(List.of(
                Arrays.copyOfRange(first, 0, split),
                Arrays.copyOfRange(first, split, first.length)
            ))).containsExactly(first);
        }
    }

    @Test
    void shouldClassifyMalformedUnsupportedAmbiguousAndDesynchronizedInput() {
        assertFailure(LengthPrefixedProtocolAdapter.MALFORMED,
            ProtocolFailureKind.MALFORMED_INPUT);
        assertFailure(LengthPrefixedProtocolAdapter.UNSUPPORTED_ENCRYPTION,
            ProtocolFailureKind.UNSUPPORTED_ENCRYPTION);
        assertFailure(LengthPrefixedProtocolAdapter.UNSUPPORTED_NEGOTIATION,
            ProtocolFailureKind.UNSUPPORTED_NEGOTIATION);
        assertFailure(LengthPrefixedProtocolAdapter.AMBIGUOUS,
            ProtocolFailureKind.AMBIGUOUS_FRAMING);
        assertFailure(LengthPrefixedProtocolAdapter.DESYNCHRONIZED,
            ProtocolFailureKind.DESYNCHRONIZATION);
    }

    @Test
    void shouldRejectExcessiveFramesAndIncompleteEof() {
        ProtocolStream<LengthPrefixedProtocolAdapter.FrameEvidence> decoder =
            decoder(LIMITS);
        byte[] excessiveHeader = ByteBuffer.allocate(Integer.BYTES)
            .putInt(LIMITS.maximumFrameBytes())
            .array();

        assertThatThrownBy(() -> decoder.decode(ByteBuffer.wrap(excessiveHeader)))
            .isInstanceOfSatisfying(
                ProtocolAdapterException.class,
                failure -> assertThat(failure.kind())
                    .isEqualTo(ProtocolFailureKind.EXCESSIVE_FRAME_SIZE)
            );

        byte[] incomplete = Arrays.copyOf(
            LengthPrefixedProtocolAdapter.frame("payload"),
            Integer.BYTES + 2
        );
        assertThatThrownBy(() -> decoder.endOfInput(ByteBuffer.wrap(incomplete)))
            .isInstanceOfSatisfying(
                ProtocolAdapterException.class,
                failure -> assertThat(failure.kind())
                    .isEqualTo(ProtocolFailureKind.DESYNCHRONIZATION)
            );
    }

    @Test
    void shouldDetachProtocolUnitBytesAndCorrelationContributionList() {
        byte[] bytes = LengthPrefixedProtocolAdapter.frame("correlated");
        CorrelationContribution<LengthPrefixedProtocolAdapter.FrameNativeReference>
            contribution = CorrelationContribution.capture(
                LengthPrefixedProtocolAdapter.correlationKey("correlated"),
                LengthPrefixedProtocolAdapter.NATIVE_REFERENCE_CODEC,
                new LengthPrefixedProtocolAdapter.FrameNativeReference(
                    FlowDirection.CONSUMER_TO_PROVIDER,
                    10,
                    LengthPrefixedProtocolAdapter.sha256("correlated".getBytes(UTF_8))
                )
            );
        List<CorrelationContribution<?>> contributions =
            new ArrayList<>(List.of(contribution));
        ProtocolUnit<String> unit = new ProtocolUnit<>(
            bytes,
            "safe-evidence",
            contributions
        );

        bytes[0] ^= 0x7f;
        contributions.clear();

        assertThat(unit.originalBytes())
            .isEqualTo(LengthPrefixedProtocolAdapter.frame("correlated"));
        assertThat(unit.correlationContributions()).containsExactly(contribution);
        assertThatThrownBy(() -> unit.correlationContributions().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    private List<byte[]> decode(List<byte[]> chunks) throws Exception {
        ProtocolStream<LengthPrefixedProtocolAdapter.FrameEvidence> decoder =
            decoder(LIMITS);
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        List<byte[]> decoded = new ArrayList<>();
        for (byte[] chunk : chunks) {
            pending.write(chunk);
            while (true) {
                ProtocolDecodeResult<LengthPrefixedProtocolAdapter.FrameEvidence> result =
                    decoder.decode(ByteBuffer.wrap(pending.toByteArray()));
                if (result instanceof ProtocolDecodeResult.NeedMoreData<?>) {
                    break;
                }
                byte[] unit = ((ProtocolDecodeResult.Complete<
                    LengthPrefixedProtocolAdapter.FrameEvidence>) result)
                    .unit().originalBytes();
                decoded.add(unit);
                byte[] remaining = Arrays.copyOfRange(
                    pending.toByteArray(),
                    unit.length,
                    pending.size()
                );
                pending.reset();
                pending.write(remaining);
            }
        }
        decoder.endOfInput(ByteBuffer.wrap(pending.toByteArray()));
        return decoded;
    }

    private void assertFailure(int value, ProtocolFailureKind expected) {
        ProtocolStream<LengthPrefixedProtocolAdapter.FrameEvidence> decoder =
            decoder(LIMITS);
        assertThatThrownBy(() -> decoder.decode(
            ByteBuffer.wrap(LengthPrefixedProtocolAdapter.control(value))
        )).isInstanceOfSatisfying(
            ProtocolAdapterException.class,
            failure -> assertThat(failure.kind()).isEqualTo(expected)
        );
    }

    private ProtocolStream<LengthPrefixedProtocolAdapter.FrameEvidence> decoder(
        ProtocolLimits limits
    ) {
        return adapter.openSession(limits)
            .openStream(FlowDirection.CONSUMER_TO_PROVIDER);
    }

    private static List<byte[]> splitEveryByte(byte[] value) {
        List<byte[]> chunks = new ArrayList<>(value.length);
        for (byte current : value) {
            chunks.add(new byte[] {current});
        }
        return chunks;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] combined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }
}
