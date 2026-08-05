package io.github.jacekkardys.systemproof.http;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import io.github.jacekkardys.systemproof.http.HttpEvidence.Acknowledgement;
import io.github.jacekkardys.systemproof.http.HttpEvidence.RequestCompleted;
import io.github.jacekkardys.systemproof.http.HttpEvidence.ResponseCompleted;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.EvidenceSchemaId;

final class HttpEvidenceCodec implements EvidenceCodec<HttpEvidence> {
    static final HttpEvidenceCodec INSTANCE = new HttpEvidenceCodec();

    private static final EvidenceSchemaId SCHEMA = new EvidenceSchemaId(
        "system-proof.http",
        "wire-evidence",
        1
    );
    private static final byte REQUEST_COMPLETED = 1;
    private static final byte RESPONSE_COMPLETED = 2;

    private HttpEvidenceCodec() {}

    @Override
    public EvidenceSchemaId schemaId() {
        return SCHEMA;
    }

    @Override
    public byte[] encode(HttpEvidence evidence) {
        if (evidence == null) {
            throw new NullPointerException("evidence must not be null");
        }
        return switch (evidence) {
            case RequestCompleted request -> encodeRequest(request);
            case ResponseCompleted response -> ByteBuffer.allocate(24)
                .order(ByteOrder.BIG_ENDIAN)
                .put(RESPONSE_COMPLETED)
                .putLong(response.exchange().sessionOrdinal())
                .putLong(response.exchange().requestOrdinal())
                .putShort((short) response.statusCode())
                .put(acknowledgementCode(response.acknowledgement()))
                .putInt(response.bodyByteCount())
                .array();
        };
    }

    @Override
    public HttpEvidence decode(byte[] encodedEvidence) {
        if (encodedEvidence == null) {
            throw new NullPointerException("encodedEvidence must not be null");
        }
        if (encodedEvidence.length < 2 || encodedEvidence.length > 128 * 1024) {
            throw new IllegalArgumentException("Invalid encoded HTTP evidence");
        }
        try {
            ByteBuffer encoded = ByteBuffer.wrap(encodedEvidence).order(ByteOrder.BIG_ENDIAN);
            byte type = encoded.get();
            HttpEvidence evidence = switch (type) {
                case REQUEST_COMPLETED -> decodeRequest(encoded);
                case RESPONSE_COMPLETED -> new ResponseCompleted(
                    getRef(encoded),
                    Short.toUnsignedInt(encoded.getShort()),
                    acknowledgement(encoded.get()),
                    encoded.getInt()
                );
                default -> throw new IllegalArgumentException(
                    "Unsupported encoded HTTP evidence type"
                );
            };
            if (encoded.hasRemaining()) {
                throw new IllegalArgumentException("Trailing encoded HTTP evidence bytes");
            }
            return evidence;
        } catch (BufferUnderflowException failure) {
            throw new IllegalArgumentException("Truncated encoded HTTP evidence");
        }
    }

    private static byte[] encodeRequest(RequestCompleted request) {
        byte[] method = request.method().getBytes(StandardCharsets.UTF_8);
        byte[] path = request.path().getBytes(StandardCharsets.UTF_8);
        byte[] contentType = request.contentType()
            .map(value -> value.getBytes(StandardCharsets.UTF_8))
            .orElseGet(() -> new byte[0]);
        int size = 1 + 16 + 4 + method.length + 4 + path.length
            + 1 + (request.contentType().isPresent() ? 4 + contentType.length : 0) + 4;
        ByteBuffer encoded = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
            .put(REQUEST_COMPLETED)
            .putLong(request.exchange().sessionOrdinal())
            .putLong(request.exchange().requestOrdinal());
        putBytes(encoded, method);
        putBytes(encoded, path);
        encoded.put((byte) (request.contentType().isPresent() ? 1 : 0));
        if (request.contentType().isPresent()) {
            putBytes(encoded, contentType);
        }
        return encoded.putInt(request.bodyByteCount()).array();
    }

    private static RequestCompleted decodeRequest(ByteBuffer encoded) {
        HttpExchangeRef exchange = getRef(encoded);
        String method = getText(encoded);
        String path = getText(encoded);
        Optional<String> contentType = switch (encoded.get()) {
            case 0 -> Optional.empty();
            case 1 -> Optional.of(getText(encoded));
            default -> throw new IllegalArgumentException(
                "Invalid encoded HTTP content type marker"
            );
        };
        return new RequestCompleted(exchange, method, path, contentType, encoded.getInt());
    }

    private static HttpExchangeRef getRef(ByteBuffer encoded) {
        return new HttpExchangeRef(encoded.getLong(), encoded.getLong());
    }

    private static void putBytes(ByteBuffer target, byte[] value) {
        target.putInt(value.length).put(value);
    }

    private static String getText(ByteBuffer source) {
        int length = source.getInt();
        if (length < 0 || length > source.remaining()) {
            throw new IllegalArgumentException("Invalid encoded HTTP text length");
        }
        ByteBuffer value = source.slice();
        value.limit(length);
        source.position(source.position() + length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(value)
                .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Invalid UTF-8 in encoded HTTP evidence");
        }
    }

    private static byte acknowledgementCode(Acknowledgement acknowledgement) {
        return switch (acknowledgement) {
            case POSITIVE -> 1;
            case NEGATIVE -> 2;
        };
    }

    private static Acknowledgement acknowledgement(byte code) {
        return switch (code) {
            case 1 -> Acknowledgement.POSITIVE;
            case 2 -> Acknowledgement.NEGATIVE;
            default -> throw new IllegalArgumentException(
                "Invalid encoded HTTP acknowledgement"
            );
        };
    }
}
