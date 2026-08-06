package io.github.jacekkardys.systemproof.smpp;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolAdapterException;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolFailureKind;

/** Exact body decoder for only the characterized reference fields and values. */
final class SmppBodyDecoder {
    private static final int MESSAGE_PAYLOAD_TAG = 0x0424;

    private final SmppProtocolLimits limits;

    SmppBodyDecoder(SmppProtocolLimits limits) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits must not be null");
    }

    void decodeBindRequest(ByteBuffer body) throws ProtocolAdapterException {
        BodyReader reader = new BodyReader(body);
        requirePrintable(reader.cOctet(16), false);
        requirePrintable(reader.cOctet(9), true);
        requireEmpty(reader.cOctet(13));
        requireValue(reader.unsignedByte(), 0x34, "SMPP interface_version is unsupported");
        requireValue(reader.unsignedByte(), 0, "SMPP bind addr_ton is unsupported");
        requireValue(reader.unsignedByte(), 0, "SMPP bind addr_npi is unsupported");
        requireEmpty(reader.cOctet(41));
        reader.requireEnd();
    }

    void decodeBindResponse(ByteBuffer body, long commandStatus)
        throws ProtocolAdapterException {
        BodyReader reader = new BodyReader(body);
        if (commandStatus == 0) {
            requirePrintable(reader.cOctet(16), false);
        }
        reader.requireEnd();
    }

    DeliverBody decodeDeliver(ByteBuffer body) throws ProtocolAdapterException {
        BodyReader reader = new BodyReader(body);
        requireEmpty(reader.cOctet(6));
        requireValue(reader.unsignedByte(), 0, "SMPP source_addr_ton is unsupported");
        requireValue(reader.unsignedByte(), 0, "SMPP source_addr_npi is unsupported");
        char[] sourceAddress = ascii(reader.cOctet(21), false);
        requireValue(reader.unsignedByte(), 0, "SMPP dest_addr_ton is unsupported");
        requireValue(reader.unsignedByte(), 0, "SMPP dest_addr_npi is unsupported");
        char[] destinationAddress = ascii(reader.cOctet(21), false);
        int esmClass = reader.unsignedByte();
        requireValue(esmClass, 0, "SMPP esm_class is unsupported");
        requireValue(reader.unsignedByte(), 0, "SMPP protocol_id is unsupported");
        requireValue(reader.unsignedByte(), 0, "SMPP priority_flag is unsupported");
        requireEmpty(reader.cOctet(17));
        requireEmpty(reader.cOctet(17));
        requireValue(reader.unsignedByte(), 0, "SMPP registered_delivery is unsupported");
        requireValue(reader.unsignedByte(), 0, "SMPP replace_if_present_flag is unsupported");
        int dataCoding = reader.unsignedByte();
        requireValue(dataCoding, 8, "SMPP data_coding is unsupported");
        requireValue(reader.unsignedByte(), 0, "SMPP sm_default_msg_id is unsupported");
        int messageLength = reader.unsignedByte();
        if (messageLength > limits.maximumShortMessageBytes()) {
            throw failure(
                ProtocolFailureKind.EXCESSIVE_FRAME_SIZE,
                "SMPP short_message length is outside the configured limit"
            );
        }
        byte[] messageBytes = reader.octets(messageLength);
        List<TlvHeader> tlvs = reader.tlvs();
        boolean hasMessagePayload = tlvs.stream()
            .anyMatch(tlv -> tlv.tag() == MESSAGE_PAYLOAD_TAG);
        if (hasMessagePayload && messageLength > 0) {
            throw failure(
                ProtocolFailureKind.AMBIGUOUS_FRAMING,
                "SMPP short_message and message_payload cannot both be present"
            );
        }
        if (!tlvs.isEmpty()) {
            throw failure(
                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                "SMPP optional parameters are unsupported by the reference subset"
            );
        }
        if (messageLength == 0) {
            throw failure(
                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                "SMPP empty short_message is unsupported by the reference subset"
            );
        }
        char[] message = ucs2(messageBytes);
        return new DeliverBody(
            sourceAddress,
            destinationAddress,
            message,
            esmClass,
            dataCoding,
            messageLength
        );
    }

    void decodeDeliverResponse(ByteBuffer body) throws ProtocolAdapterException {
        BodyReader reader = new BodyReader(body);
        requireEmpty(reader.cOctet(1));
        reader.requireEnd();
    }

    void requireEmptyBody(ByteBuffer body) throws ProtocolAdapterException {
        new BodyReader(body).requireEnd();
    }

    private static char[] ascii(byte[] bytes, boolean allowEmpty)
        throws ProtocolAdapterException {
        requirePrintable(bytes, allowEmpty);
        char[] characters = new char[bytes.length];
        for (int index = 0; index < bytes.length; index++) {
            characters[index] = (char) Byte.toUnsignedInt(bytes[index]);
        }
        return characters;
    }

    private static void requirePrintable(byte[] bytes, boolean allowEmpty)
        throws ProtocolAdapterException {
        if (!allowEmpty && bytes.length == 0) {
            throw failure(ProtocolFailureKind.MALFORMED_INPUT, "SMPP C-Octet value is empty");
        }
        for (byte value : bytes) {
            int unsigned = Byte.toUnsignedInt(value);
            if (unsigned < 0x20 || unsigned > 0x7e) {
                throw failure(
                    ProtocolFailureKind.MALFORMED_INPUT,
                    "SMPP C-Octet value is outside the characterized ASCII subset"
                );
            }
        }
    }

    private static char[] ucs2(byte[] bytes) throws ProtocolAdapterException {
        if ((bytes.length & 1) != 0) {
            throw failure(
                ProtocolFailureKind.MALFORMED_INPUT,
                "SMPP UCS2 short_message has an odd byte count"
            );
        }
        try {
            CharBuffer decoded = StandardCharsets.UTF_16BE.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
            char[] result = new char[decoded.remaining()];
            decoded.get(result);
            return result;
        } catch (CharacterCodingException failure) {
            throw SmppBodyDecoder.failure(
                ProtocolFailureKind.MALFORMED_INPUT,
                "SMPP UCS2 short_message is malformed"
            );
        }
    }

    private static void requireEmpty(byte[] value) throws ProtocolAdapterException {
        if (value.length != 0) {
            throw failure(
                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                "SMPP field must be empty in the reference subset"
            );
        }
    }

    private static void requireValue(int actual, int expected, String message)
        throws ProtocolAdapterException {
        if (actual != expected) {
            throw failure(ProtocolFailureKind.UNSUPPORTED_NEGOTIATION, message);
        }
    }

    private static ProtocolAdapterException failure(
        ProtocolFailureKind kind,
        String message
    ) {
        return new ProtocolAdapterException(kind, message);
    }

    record DeliverBody(
        char[] sourceAddress,
        char[] destinationAddress,
        char[] message,
        int esmClass,
        int dataCoding,
        int messageByteCount
    ) {
        DeliverBody {
            sourceAddress = sourceAddress.clone();
            destinationAddress = destinationAddress.clone();
            message = message.clone();
        }

        @Override
        public char[] sourceAddress() {
            return sourceAddress.clone();
        }

        @Override
        public char[] destinationAddress() {
            return destinationAddress.clone();
        }

        @Override
        public char[] message() {
            return message.clone();
        }
    }

    private record TlvHeader(int tag, int length) {}

    private static final class BodyReader {
        private final ByteBuffer bytes;

        private BodyReader(ByteBuffer bytes) {
            this.bytes = java.util.Objects.requireNonNull(
                bytes,
                "body must not be null"
            ).slice();
        }

        private int unsignedByte() throws ProtocolAdapterException {
            requireRemaining(1);
            return Byte.toUnsignedInt(bytes.get());
        }

        private byte[] cOctet(int maximumBytes) throws ProtocolAdapterException {
            int start = bytes.position();
            for (int length = 0; length < maximumBytes; length++) {
                requireRemaining(1);
                if (bytes.get() == 0) {
                    int valueLength = bytes.position() - start - 1;
                    byte[] result = new byte[valueLength];
                    ByteBuffer value = bytes.duplicate();
                    value.position(start).limit(start + valueLength);
                    value.get(result);
                    return result;
                }
            }
            throw failure(
                ProtocolFailureKind.MALFORMED_INPUT,
                "SMPP C-Octet string is not terminated within its field limit"
            );
        }

        private byte[] octets(int length) throws ProtocolAdapterException {
            requireRemaining(length);
            byte[] result = new byte[length];
            bytes.get(result);
            return result;
        }

        private List<TlvHeader> tlvs() throws ProtocolAdapterException {
            List<TlvHeader> result = new ArrayList<>();
            while (bytes.hasRemaining()) {
                requireRemaining(4);
                int tag = Short.toUnsignedInt(bytes.getShort());
                int length = Short.toUnsignedInt(bytes.getShort());
                requireRemaining(length);
                bytes.position(bytes.position() + length);
                result.add(new TlvHeader(tag, length));
            }
            return List.copyOf(result);
        }

        private void requireEnd() throws ProtocolAdapterException {
            if (bytes.hasRemaining()) {
                throw failure(
                    ProtocolFailureKind.MALFORMED_INPUT,
                    "SMPP PDU body contains trailing bytes"
                );
            }
        }

        private void requireRemaining(int length) throws ProtocolAdapterException {
            if (length < 0 || bytes.remaining() < length) {
                throw failure(
                    ProtocolFailureKind.MALFORMED_INPUT,
                    "SMPP PDU body is truncated"
                );
            }
        }
    }
}
