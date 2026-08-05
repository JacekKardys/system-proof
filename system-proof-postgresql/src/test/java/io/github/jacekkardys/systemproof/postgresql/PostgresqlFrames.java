package io.github.jacekkardys.systemproof.postgresql;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class PostgresqlFrames {
    private PostgresqlFrames() {}

    static byte[] sslRequest() {
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(8)
            .putInt(80877103)
            .array();
    }

    static byte[] startup() {
        byte[] parameters = bytes("user\0test\0database\0test\0\0");
        return ByteBuffer.allocate(8 + parameters.length).order(ByteOrder.BIG_ENDIAN)
            .putInt(8 + parameters.length)
            .putInt(196608)
            .put(parameters)
            .array();
    }

    static byte[] query(String sql) {
        return message('Q', cstring(sql));
    }

    static byte[] parse(String statement, String sql) {
        return message('P', concat(cstring(statement), cstring(sql), int16(0)));
    }

    static byte[] parse(String statement, String sql, int... parameterTypeOids) {
        ByteBuffer types = ByteBuffer.allocate(
            Short.BYTES + parameterTypeOids.length * Integer.BYTES
        ).order(ByteOrder.BIG_ENDIAN);
        types.putShort((short) parameterTypeOids.length);
        Arrays.stream(parameterTypeOids).forEach(types::putInt);
        return message('P', concat(cstring(statement), cstring(sql), types.array()));
    }

    static byte[] bind(String portal, String statement, String... values) {
        return bind(portal, statement, new int[0], values);
    }

    static byte[] bind(
        String portal,
        String statement,
        int[] parameterFormats,
        String... values
    ) {
        int size = cstring(portal).length
            + cstring(statement).length
            + Short.BYTES
            + parameterFormats.length * Short.BYTES
            + Short.BYTES
            + Short.BYTES;
        for (String value : values) {
            size += Integer.BYTES + (value == null ? 0 : bytes(value).length);
        }
        ByteBuffer payload = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        payload.put(cstring(portal)).put(cstring(statement))
            .putShort((short) parameterFormats.length);
        Arrays.stream(parameterFormats).forEach(format ->
            payload.putShort((short) format)
        );
        payload.putShort((short) values.length);
        for (String value : values) {
            if (value == null) {
                payload.putInt(-1);
            } else {
                byte[] encoded = bytes(value);
                payload.putInt(encoded.length).put(encoded);
            }
        }
        payload.putShort((short) 0);
        return message('B', payload.array());
    }

    static byte[] describePortal(String portal) {
        return message('D', concat(new byte[] {'P'}, cstring(portal)));
    }

    static byte[] execute(String portal) {
        return execute(portal, 0);
    }

    static byte[] execute(String portal, int maximumRows) {
        return message('E', concat(cstring(portal), int32(maximumRows)));
    }

    static byte[] closeStatement(String statement) {
        return message('C', concat(new byte[] {'S'}, cstring(statement)));
    }

    static byte[] sync() {
        return message('S', new byte[0]);
    }

    static byte[] flush() {
        return message('H', new byte[0]);
    }

    static byte[] terminate() {
        return message('X', new byte[0]);
    }

    static byte[] password(String secret) {
        return message('p', cstring(secret));
    }

    static byte[] parseComplete() {
        return message('1', new byte[0]);
    }

    static byte[] bindComplete() {
        return message('2', new byte[0]);
    }

    static byte[] noData() {
        return message('n', new byte[0]);
    }

    static byte[] commandComplete(String tag) {
        return message('C', cstring(tag));
    }

    static byte[] ready(char status) {
        return message('Z', new byte[] {(byte) status});
    }

    static byte[] backendKeyData(int backendPid) {
        return message(
            'K',
            ByteBuffer.allocate(Integer.BYTES * 2)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(backendPid)
                .putInt(123456789)
                .array()
        );
    }

    static byte[] error() {
        return message('E', new byte[] {'S', 'E', 'R', 'R', 'O', 'R', 0, 0});
    }

    static byte[] notice() {
        return message('N', new byte[] {'S', 'N', 'O', 'T', 'I', 'C', 'E', 0, 0});
    }

    static byte[] message(char type, byte[] payload) {
        return ByteBuffer.allocate(1 + Integer.BYTES + payload.length)
            .order(ByteOrder.BIG_ENDIAN)
            .put((byte) type)
            .putInt(Integer.BYTES + payload.length)
            .put(payload)
            .array();
    }

    static byte[] concat(byte[]... values) {
        int length = Arrays.stream(values).mapToInt(value -> value.length).sum();
        ByteBuffer result = ByteBuffer.allocate(length);
        Arrays.stream(values).forEach(result::put);
        return result.array();
    }

    private static byte[] cstring(String value) {
        return concat(bytes(value), new byte[] {0});
    }

    private static byte[] int16(int value) {
        return ByteBuffer.allocate(Short.BYTES).order(ByteOrder.BIG_ENDIAN)
            .putShort((short) value)
            .array();
    }

    private static byte[] int32(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(value)
            .array();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
