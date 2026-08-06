package io.github.jacekkardys.systemproof.http;

import java.nio.charset.StandardCharsets;

final class HttpMessages {
    private HttpMessages() {}

    static byte[] request(String body) {
        return request("POST", "/v1/ingestion/sms", body);
    }

    static byte[] request(String method, String path, String body) {
        return request(
            method,
            path,
            "application/x-www-form-urlencoded",
            body
        );
    }

    static byte[] request(
        String method,
        String path,
        String contentType,
        String body,
        String... additionalHeaders
    ) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder request = new StringBuilder()
            .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            .append("Host: ingestion:8080\r\n")
            .append("Content-Type: ").append(contentType).append("\r\n")
            .append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        for (String header : additionalHeaders) {
            request.append(header).append("\r\n");
        }
        return request.append("\r\n").append(body).toString()
            .getBytes(StandardCharsets.UTF_8);
    }

    static byte[] response(int status, String body, String... additionalHeaders) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String[] headers = new String[additionalHeaders.length + 1];
        headers[0] = "Content-Type: text/plain;charset=UTF-8";
        System.arraycopy(additionalHeaders, 0, headers, 1, additionalHeaders.length);
        return responseBytes(status, bodyBytes, headers);
    }

    static byte[] responseBytes(int status, byte[] body, String... headers) {
        StringBuilder response = new StringBuilder()
            .append("HTTP/1.1 ").append(status).append(" Test\r\n");
        for (String header : headers) {
            response.append(header).append("\r\n");
        }
        byte[] prefix = response.append("Content-Length: ")
            .append(body.length)
            .append("\r\n\r\n")
            .toString()
            .getBytes(StandardCharsets.US_ASCII);
        return concat(prefix, body);
    }

    static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
