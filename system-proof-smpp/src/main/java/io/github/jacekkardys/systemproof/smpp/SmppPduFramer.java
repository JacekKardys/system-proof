package io.github.jacekkardys.systemproof.smpp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolAdapterException;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolFailureKind;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;

/** Package-private exact-byte SMPP PDU framer. */
final class SmppPduFramer {
    static final int HEADER_BYTES = 16;

    private final int maximumPduBytes;

    SmppPduFramer(ProtocolLimits gatewayLimits, SmppProtocolLimits smppLimits) {
        Objects.requireNonNull(gatewayLimits, "gatewayLimits must not be null");
        Objects.requireNonNull(smppLimits, "smppLimits must not be null");
        maximumPduBytes = Math.min(
            gatewayLimits.maximumFrameBytes(),
            smppLimits.maximumPduBytes()
        );
    }

    Frame decode(ByteBuffer bufferedBytes) throws ProtocolAdapterException {
        Objects.requireNonNull(bufferedBytes, "bufferedBytes must not be null");
        if (bufferedBytes.remaining() < Integer.BYTES) {
            return null;
        }
        ByteBuffer bytes = bufferedBytes.slice().order(ByteOrder.BIG_ENDIAN);
        long commandLength = Integer.toUnsignedLong(bytes.getInt());
        if (commandLength < HEADER_BYTES) {
            throw failure(
                ProtocolFailureKind.MALFORMED_INPUT,
                "SMPP command_length is below the common header size"
            );
        }
        if (commandLength > maximumPduBytes) {
            throw failure(
                ProtocolFailureKind.EXCESSIVE_FRAME_SIZE,
                "SMPP command_length exceeds the configured PDU limit"
            );
        }
        if (bufferedBytes.remaining() < HEADER_BYTES) {
            return null;
        }
        int length = Math.toIntExact(commandLength);
        if (bufferedBytes.remaining() < length) {
            return null;
        }
        long commandId = Integer.toUnsignedLong(bytes.getInt());
        long commandStatus = Integer.toUnsignedLong(bytes.getInt());
        long sequenceNumber = Integer.toUnsignedLong(bytes.getInt());
        byte[] originalBytes = new byte[length];
        bufferedBytes.slice().get(originalBytes);
        return new Frame(
            originalBytes,
            commandId,
            commandStatus,
            sequenceNumber
        );
    }

    private static ProtocolAdapterException failure(
        ProtocolFailureKind kind,
        String message
    ) {
        return new ProtocolAdapterException(kind, message);
    }

    record Frame(
        byte[] originalBytes,
        long commandId,
        long commandStatus,
        long sequenceNumber
    ) {
        Frame {
            originalBytes = Objects.requireNonNull(
                originalBytes,
                "originalBytes must not be null"
            ).clone();
        }

        @Override
        public byte[] originalBytes() {
            return originalBytes.clone();
        }

        int pduByteCount() {
            return originalBytes.length;
        }

        ByteBuffer body() {
            return ByteBuffer.wrap(
                originalBytes,
                HEADER_BYTES,
                originalBytes.length - HEADER_BYTES
            ).slice().asReadOnlyBuffer();
        }
    }
}
