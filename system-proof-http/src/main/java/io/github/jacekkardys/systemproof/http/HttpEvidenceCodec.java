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
import io.github.jacekkardys.systemproof.http.HttpEvidence.RequestMethod;
import io.github.jacekkardys.systemproof.http.HttpEvidence.RequestTarget;
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
    private static final int TARGET_DIGEST_BYTES = 32;
    private static final int MAXIMUM_ENCODED_EVIDENCE_BYTES =
        HttpProtocolLimits.MAXIMUM_HEADER_SECTION_BYTES + 63;

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
        if (encodedEvidence.length < 2
            || encodedEvidence.length > MAXIMUM_ENCODED_EVIDENCE_BYTES) {
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
        byte[] targetDigest = java.util.HexFormat.of().parseHex(request.target().sha256());
        byte[] contentType = request.contentType()
            .map(value -> value.getBytes(StandardCharsets.UTF_8))
            .orElseGet(() -> new byte[0]);
        int size = 1 + 16 + 1 + 4 + TARGET_DIGEST_BYTES
            + 1 + (request.contentType().isPresent() ? 4 + contentType.length : 0) + 4;
        ByteBuffer encoded = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
            .put(REQUEST_COMPLETED)
            .putLong(request.exchange().sessionOrdinal())
            .putLong(request.exchange().requestOrdinal())
            .put(requestMethodCode(request.method()))
            .putInt(request.target().byteCount())
            .put(targetDigest);
        encoded.put((byte) (request.contentType().isPresent() ? 1 : 0));
        if (request.contentType().isPresent()) {
            putBytes(encoded, contentType);
        }
        return encoded.putInt(request.bodyByteCount()).array();
    }

    private static RequestCompleted decodeRequest(ByteBuffer encoded) {
        HttpExchangeRef exchange = getRef(encoded);
        RequestMethod method = requestMethod(encoded.get());
        int targetByteCount = encoded.getInt();
        byte[] targetDigest = new byte[TARGET_DIGEST_BYTES];
        encoded.get(targetDigest);
        RequestTarget target = new RequestTarget(
            targetByteCount,
            java.util.HexFormat.of().formatHex(targetDigest)
        );
        Optional<String> contentType = switch (encoded.get()) {
            case 0 -> Optional.empty();
            case 1 -> Optional.of(getText(encoded));
            default -> throw new IllegalArgumentException(
                "Invalid encoded HTTP content type marker"
            );
        };
        return new RequestCompleted(exchange, method, target, contentType, encoded.getInt());
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
            case INDETERMINATE -> 2;
            case NEGATIVE -> 3;
        };
    }

    private static Acknowledgement acknowledgement(byte code) {
        return switch (code) {
            case 1 -> Acknowledgement.POSITIVE;
            case 2 -> Acknowledgement.INDETERMINATE;
            case 3 -> Acknowledgement.NEGATIVE;
            default -> throw new IllegalArgumentException(
                "Invalid encoded HTTP acknowledgement"
            );
        };
    }

    private static byte requestMethodCode(RequestMethod method) {
        return switch (method) {
            case POST -> 1;
            case OTHER -> 2;
        };
    }

    private static RequestMethod requestMethod(byte code) {
        return switch (code) {
            case 1 -> RequestMethod.POST;
            case 2 -> RequestMethod.OTHER;
            default -> throw new IllegalArgumentException("Invalid encoded HTTP request method");
        };
    }
}
