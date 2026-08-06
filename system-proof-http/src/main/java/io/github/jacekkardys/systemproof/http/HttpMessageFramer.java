package io.github.jacekkardys.systemproof.http;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolAdapterException;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolFailureKind;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;

/** Stateless framing for the explicitly supported HTTP/1.1 subset. */
final class HttpMessageFramer {
    private final ProtocolLimits gatewayLimits;
    private final HttpProtocolLimits httpLimits;

    HttpMessageFramer(
        ProtocolLimits gatewayLimits,
        HttpProtocolLimits httpLimits
    ) {
        this.gatewayLimits = Objects.requireNonNull(
            gatewayLimits,
            "gatewayLimits must not be null"
        );
        this.httpLimits = Objects.requireNonNull(httpLimits, "httpLimits must not be null");
    }

    RequestFrame decodeRequest(ByteBuffer bufferedBytes)
        throws ProtocolAdapterException {
        ParsedMessage parsed = parse(bufferedBytes, true);
        if (parsed == null) {
            return null;
        }
        RequestLine request = requestLine(parsed.startLine());
        validateRequestHeaders(parsed.headers());
        return new RequestFrame(
            request.method(),
            request.path(),
            requestContentType(parsed.headers()),
            parsed.closesConnection(),
            parsed.headerByteCount(),
            parsed.bodyByteCount(),
            parsed.frameByteCount()
        );
    }

    ResponseFrame decodeResponse(ByteBuffer bufferedBytes)
        throws ProtocolAdapterException {
        ParsedMessage parsed = parse(bufferedBytes, false);
        if (parsed == null) {
            return null;
        }
        int statusCode = statusCode(parsed.startLine());
        if (statusCode < 200) {
            throw failure(
                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                "Informational HTTP responses are unsupported"
            );
        }
        if (statusCode >= 300 && statusCode < 400) {
            throw failure(
                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                "HTTP redirects are outside the characterized response subset"
            );
        }
        if (parsed.headers().containsKey("content-encoding")) {
            throw failure(
                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                "HTTP Content-Encoding is outside the characterized response subset"
            );
        }
        return new ResponseFrame(
            statusCode,
            responseTextEncoding(parsed.headers()),
            parsed.closesConnection(),
            parsed.headerByteCount(),
            parsed.bodyByteCount(),
            parsed.frameByteCount()
        );
    }

    byte[] copyOriginal(ByteBuffer source, Frame frame) {
        ByteBuffer view = source.asReadOnlyBuffer();
        view.limit(view.position() + frame.frameByteCount());
        byte[] copy = new byte[frame.frameByteCount()];
        view.get(copy);
        return copy;
    }

    private ParsedMessage parse(ByteBuffer bufferedBytes, boolean request)
        throws ProtocolAdapterException {
        ByteBuffer source = bufferedBytes.asReadOnlyBuffer();
        int start = source.position();
        if (looksLikeTls(source)) {
            throw failure(
                ProtocolFailureKind.UNSUPPORTED_ENCRYPTION,
                "TLS is unsupported by the plaintext HTTP adapter"
            );
        }
        int startLineEnd = findCrlf(source, start);
        if (startLineEnd < 0) {
            if (source.remaining() > httpLimits.maximumStartLineBytes()) {
                throw excessive("HTTP start line exceeds the configured limit");
            }
            return null;
        }
        if (startLineEnd - start > httpLimits.maximumStartLineBytes()) {
            throw excessive("HTTP start line exceeds the configured limit");
        }
        int headerEnd = findHeaderEnd(source, startLineEnd + 2);
        if (headerEnd < 0) {
            if (source.remaining() > httpLimits.maximumHeaderSectionBytes()) {
                throw excessive("HTTP header section exceeds the configured limit");
            }
            return null;
        }
        int headerBytes = headerEnd - start;
        if (headerBytes > httpLimits.maximumHeaderSectionBytes()) {
            throw excessive("HTTP header section exceeds the configured limit");
        }
        String startLine = ascii(source, start, startLineEnd);
        Map<String, List<String>> headers = parseHeaders(
            source,
            startLineEnd + 2,
            headerEnd - 2
        );
        Set<String> connectionTokens = validateCommonHeaders(headers);
        OptionalLong contentLength = contentLength(headers);
        long bodyBytes;
        if (request) {
            bodyBytes = contentLength.orElse(0L);
        } else {
            int status = statusCode(startLine);
            if (status == 204 || status == 304) {
                bodyBytes = contentLength.orElse(0L);
                if (bodyBytes != 0) {
                    throw malformed("Body bytes are forbidden for this HTTP response status");
                }
            } else if (contentLength.isPresent()) {
                bodyBytes = contentLength.getAsLong();
            } else {
                throw failure(
                    ProtocolFailureKind.AMBIGUOUS_FRAMING,
                    "Close-delimited HTTP responses are unsupported"
                );
            }
        }
        if (bodyBytes > httpLimits.maximumBodyBytes()) {
            throw excessive("HTTP body exceeds the configured limit");
        }
        long frameBytes = headerBytes + bodyBytes;
        if (frameBytes > gatewayLimits.maximumFrameBytes()) {
            throw excessive("HTTP message exceeds the gateway frame limit");
        }
        if (source.remaining() < frameBytes) {
            return null;
        }
        return new ParsedMessage(
            startLine,
            headers,
            connectionTokens.contains("close"),
            headerBytes,
            Math.toIntExact(bodyBytes),
            Math.toIntExact(frameBytes)
        );
    }

    private Map<String, List<String>> parseHeaders(
        ByteBuffer source,
        int firstHeader,
        int terminatingCrlf
    ) throws ProtocolAdapterException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        int offset = firstHeader;
        int count = 0;
        while (offset < terminatingCrlf) {
            int end = findCrlf(source, offset);
            if (end < 0 || end > terminatingCrlf) {
                throw malformed("Malformed HTTP header line termination");
            }
            if (++count > httpLimits.maximumHeaderCount()) {
                throw excessive("HTTP header count exceeds the configured limit");
            }
            String line = ascii(source, offset, end);
            if (line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw malformed("HTTP obsolete line folding is unsupported");
            }
            int colon = line.indexOf(':');
            if (colon < 1 || !token(line.substring(0, colon))) {
                throw malformed("Invalid HTTP header field name");
            }
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = trimOptionalWhitespace(line.substring(colon + 1));
            if (!validHeaderValue(value)) {
                throw malformed("Invalid HTTP header field value");
            }
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            offset = end + 2;
        }
        return headers.entrySet().stream().collect(
            LinkedHashMap::new,
            (copy, entry) -> copy.put(entry.getKey(), List.copyOf(entry.getValue())),
            LinkedHashMap::putAll
        );
    }

    private static void validateRequestHeaders(Map<String, List<String>> headers)
        throws ProtocolAdapterException {
        List<String> hosts = headers.get("host");
        if (hosts == null || hosts.size() != 1 || hosts.getFirst().isBlank()) {
            throw malformed("HTTP/1.1 requests require one Host header");
        }
        if (headers.containsKey("expect")) {
            throw failure(
                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                "HTTP Expect negotiation is unsupported"
            );
        }
    }

    private static Set<String> validateCommonHeaders(Map<String, List<String>> headers)
        throws ProtocolAdapterException {
        boolean hasTransferEncoding = headers.containsKey("transfer-encoding");
        boolean hasContentLength = headers.containsKey("content-length");
        if (hasTransferEncoding && hasContentLength) {
            throw failure(
                ProtocolFailureKind.AMBIGUOUS_FRAMING,
                "HTTP Transfer-Encoding and Content-Length conflict"
            );
        }
        if (hasTransferEncoding) {
            throw failure(
                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                "HTTP transfer codings are unsupported"
            );
        }
        Set<String> connectionTokens = connectionTokens(headers);
        if (headers.containsKey("upgrade") || connectionTokens.contains("upgrade")) {
            throw failure(
                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                "HTTP protocol upgrades are unsupported"
            );
        }
        return connectionTokens;
    }

    private static Set<String> connectionTokens(Map<String, List<String>> headers)
        throws ProtocolAdapterException {
        List<String> tokens = new ArrayList<>();
        for (String value : headers.getOrDefault("connection", List.of())) {
            for (String candidate : value.split(",", -1)) {
                String normalized = candidate.strip().toLowerCase(Locale.ROOT);
                if (!token(normalized)) {
                    throw malformed("Invalid HTTP Connection option");
                }
                tokens.add(normalized);
            }
        }
        return Set.copyOf(tokens);
    }

    private static OptionalLong contentLength(Map<String, List<String>> headers)
        throws ProtocolAdapterException {
        List<String> values = headers.get("content-length");
        if (values == null) {
            return OptionalLong.empty();
        }
        long result = -1;
        for (String value : values) {
            if (!value.matches("[0-9]+")) {
                throw malformed("Invalid HTTP Content-Length");
            }
            long current;
            try {
                current = Long.parseLong(value);
            } catch (NumberFormatException failure) {
                throw malformed("Invalid HTTP Content-Length");
            }
            if (result >= 0 && result != current) {
                throw failure(
                    ProtocolFailureKind.AMBIGUOUS_FRAMING,
                    "Conflicting HTTP Content-Length fields"
                );
            }
            result = current;
        }
        return OptionalLong.of(result);
    }

    private static Optional<String> requestContentType(Map<String, List<String>> headers)
        throws ProtocolAdapterException {
        List<String> values = headers.get("content-type");
        if (values == null) {
            return Optional.empty();
        }
        if (values.size() != 1 || values.getFirst().isBlank()) {
            throw failure(
                ProtocolFailureKind.AMBIGUOUS_FRAMING,
                "HTTP Content-Type must be singular and non-blank"
            );
        }
        String value = values.getFirst();
        if (value.indexOf(';') >= 0) {
            throw failure(
                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                "HTTP request Content-Type parameters are unsupported"
            );
        }
        int slash = value.indexOf('/');
        if (slash < 1 || slash == value.length() - 1
            || value.indexOf('/', slash + 1) >= 0
            || !token(value.substring(0, slash))
            || !token(value.substring(slash + 1))) {
            throw malformed("Invalid HTTP Content-Type media type");
        }
        return Optional.of(value.toLowerCase(Locale.ROOT));
    }

    private static ResponseTextEncoding responseTextEncoding(
        Map<String, List<String>> headers
    ) throws ProtocolAdapterException {
        List<String> values = headers.get("content-type");
        if (values == null) {
            return ResponseTextEncoding.ISO_8859_1;
        }
        if (values.size() != 1 || values.getFirst().isBlank()) {
            throw failure(
                ProtocolFailureKind.AMBIGUOUS_FRAMING,
                "HTTP response Content-Type must be singular and non-blank"
            );
        }
        String[] parts = values.getFirst().split(";", -1);
        if (!parts[0].strip().equalsIgnoreCase("text/plain")) {
            throw failure(
                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                "HTTP response media type is outside the characterized subset"
            );
        }
        if (parts.length == 1) {
            return ResponseTextEncoding.ISO_8859_1;
        }
        if (parts.length != 2) {
            throw failure(
                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                "HTTP response Content-Type parameters are outside the characterized subset"
            );
        }
        String parameter = parts[1].strip();
        int equals = parameter.indexOf('=');
        if (equals < 1
            || !parameter.substring(0, equals).strip().equalsIgnoreCase("charset")) {
            throw failure(
                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                "HTTP response Content-Type parameter is outside the characterized subset"
            );
        }
        String charset = parameter.substring(equals + 1).strip();
        if (!charset.equalsIgnoreCase("UTF-8")) {
            throw failure(
                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                "HTTP response charset is outside the characterized subset"
            );
        }
        return ResponseTextEncoding.UTF_8;
    }

    private static RequestLine requestLine(String line) throws ProtocolAdapterException {
        int firstSpace = line.indexOf(' ');
        int secondSpace = firstSpace < 0 ? -1 : line.indexOf(' ', firstSpace + 1);
        if (firstSpace < 1 || secondSpace <= firstSpace + 1
            || line.indexOf(' ', secondSpace + 1) >= 0) {
            throw malformed("Malformed HTTP request line");
        }
        String method = line.substring(0, firstSpace);
        String target = line.substring(firstSpace + 1, secondSpace);
        String version = line.substring(secondSpace + 1);
        if (!token(method)) {
            throw malformed("Invalid HTTP request method");
        }
        if (method.equals("CONNECT") || method.equals("HEAD")) {
            throw failure(
                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                "HTTP method is outside the characterized subset"
            );
        }
        if (!version.equals("HTTP/1.1")) {
            throw failure(
                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                "Only HTTP/1.1 is supported"
            );
        }
        if (!target.startsWith("/")
            || target.indexOf('?') >= 0
            || target.indexOf('#') >= 0) {
            throw failure(
                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                "Only query-free HTTP origin-form request targets are supported"
            );
        }
        if (target.isEmpty()) {
            throw malformed("HTTP request path must not be empty");
        }
        return new RequestLine(method, target);
    }

    private static int statusCode(String line) throws ProtocolAdapterException {
        if (!line.startsWith("HTTP/1.1 ") || line.length() < 12) {
            throw malformed("Malformed HTTP status line");
        }
        String digits = line.substring(9, 12);
        if (!digits.matches("[0-9]{3}")
            || (line.length() > 12 && line.charAt(12) != ' ')) {
            throw malformed("Malformed HTTP status line");
        }
        int status = Integer.parseInt(digits);
        if (status < 100 || status > 599) {
            throw malformed("HTTP status code is outside the supported range");
        }
        return status;
    }

    private static boolean looksLikeTls(ByteBuffer source) {
        return source.remaining() >= 3
            && Byte.toUnsignedInt(source.get(source.position())) == 0x16
            && Byte.toUnsignedInt(source.get(source.position() + 1)) == 0x03;
    }

    private static int findHeaderEnd(ByteBuffer source, int offset)
        throws ProtocolAdapterException {
        int lineStart = offset;
        while (lineStart < source.limit()) {
            int lineEnd = findCrlf(source, lineStart);
            if (lineEnd < 0) {
                return -1;
            }
            if (lineEnd == lineStart) {
                return lineEnd + 2;
            }
            lineStart = lineEnd + 2;
        }
        return -1;
    }

    private static int findCrlf(ByteBuffer source, int offset)
        throws ProtocolAdapterException {
        for (int index = offset; index < source.limit(); index++) {
            byte current = source.get(index);
            if (current == '\n') {
                if (index == offset || source.get(index - 1) != '\r') {
                    throw malformed("HTTP lines must use CRLF termination");
                }
                return index - 1;
            }
            if (current == '\r' && index + 1 < source.limit()
                && source.get(index + 1) != '\n') {
                throw malformed("HTTP lines must use CRLF termination");
            }
        }
        return -1;
    }

    private static String ascii(ByteBuffer source, int start, int end)
        throws ProtocolAdapterException {
        StringBuilder value = new StringBuilder(end - start);
        for (int index = start; index < end; index++) {
            int current = Byte.toUnsignedInt(source.get(index));
            if (current < 0x20 || current > 0x7e) {
                throw malformed("HTTP start lines and headers must contain visible ASCII");
            }
            value.append((char) current);
        }
        return value.toString();
    }

    private static boolean validHeaderValue(String value) {
        return value.chars().allMatch(character ->
            character == '\t' || (character >= 0x20 && character <= 0x7e)
        );
    }

    private static String trimOptionalWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean token(String value) {
        return !value.isEmpty() && value.chars().allMatch(character ->
            Character.isLetterOrDigit(character)
                || "!#$%&'*+-.^_`|~".indexOf(character) >= 0
        );
    }

    private static ProtocolAdapterException excessive(String message) {
        return failure(ProtocolFailureKind.EXCESSIVE_FRAME_SIZE, message);
    }

    private static ProtocolAdapterException malformed(String message) {
        return failure(ProtocolFailureKind.MALFORMED_INPUT, message);
    }

    private static ProtocolAdapterException failure(
        ProtocolFailureKind kind,
        String message
    ) {
        return new ProtocolAdapterException(kind, message);
    }

    sealed interface Frame permits RequestFrame, ResponseFrame {
        int headerByteCount();

        int bodyByteCount();

        int frameByteCount();
    }

    record RequestFrame(
        String method,
        String path,
        Optional<String> contentType,
        boolean closesConnection,
        int headerByteCount,
        int bodyByteCount,
        int frameByteCount
    ) implements Frame {}

    record ResponseFrame(
        int statusCode,
        ResponseTextEncoding textEncoding,
        boolean closesConnection,
        int headerByteCount,
        int bodyByteCount,
        int frameByteCount
    ) implements Frame {}

    private record ParsedMessage(
        String startLine,
        Map<String, List<String>> headers,
        boolean closesConnection,
        int headerByteCount,
        int bodyByteCount,
        int frameByteCount
    ) {}

    private record RequestLine(String method, String path) {}

    enum ResponseTextEncoding {
        UTF_8,
        ISO_8859_1
    }
}
