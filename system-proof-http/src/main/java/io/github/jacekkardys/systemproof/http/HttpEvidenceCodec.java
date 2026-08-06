package io.github.jacekkardys.systemproof.http;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import io.github.jacekkardys.systemproof.http.HttpEvidence.Acknowledgement;
import io.github.jacekkardys.systemproof.http.HttpEvidence.RequestContentType;
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
    private static final int MAXIMUM_ENCODED_EVIDENCE_BYTES = 59;

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
        return ByteBuffer.allocate(MAXIMUM_ENCODED_EVIDENCE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(REQUEST_COMPLETED)
            .putLong(request.exchange().sessionOrdinal())
            .putLong(request.exchange().requestOrdinal())
            .put(requestMethodCode(request.method()))
            .putInt(request.target().byteCount())
            .put(targetDigest)
            .put(requestContentTypeCode(request.contentType()))
            .putInt(request.bodyByteCount())
            .array();
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
        RequestContentType contentType = requestContentType(encoded.get());
        return new RequestCompleted(exchange, method, target, contentType, encoded.getInt());
    }

    private static HttpExchangeRef getRef(ByteBuffer encoded) {
        return new HttpExchangeRef(encoded.getLong(), encoded.getLong());
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

    private static byte requestContentTypeCode(RequestContentType contentType) {
        return switch (contentType) {
            case ABSENT -> 1;
            case FORM_URLENCODED -> 2;
            case OTHER -> 3;
        };
    }

    private static RequestContentType requestContentType(byte code) {
        return switch (code) {
            case 1 -> RequestContentType.ABSENT;
            case 2 -> RequestContentType.FORM_URLENCODED;
            case 3 -> RequestContentType.OTHER;
            default -> throw new IllegalArgumentException(
                "Invalid encoded HTTP request content type"
            );
        };
    }
}
