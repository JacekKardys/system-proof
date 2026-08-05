package io.github.jacekkardys.systemproof.http;

import java.nio.charset.StandardCharsets;

final class HttpMessages {
    private HttpMessages() {}

    static byte[] request(String body) {
        return request("POST", "/v1/ingestion/sms", body);
    }

    static byte[] request(String method, String path, String body) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        return (method + " " + path + " HTTP/1.1\r\n"
            + "Host: ingestion:8080\r\n"
            + "Content-Type: application/x-www-form-urlencoded\r\n"
            + "Content-Length: " + bodyBytes.length + "\r\n"
            + "\r\n" + body).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] response(int status, String body) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        return ("HTTP/1.1 " + status + " Test\r\n"
            + "Content-Type: text/plain;charset=UTF-8\r\n"
            + "Content-Length: " + bodyBytes.length + "\r\n"
            + "\r\n" + body).getBytes(StandardCharsets.UTF_8);
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
