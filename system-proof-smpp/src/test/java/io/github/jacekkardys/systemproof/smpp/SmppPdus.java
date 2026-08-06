package io.github.jacekkardys.systemproof.smpp;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class SmppPdus {
    static final long BIND_TRANSCEIVER = 0x00000009L;
    static final long BIND_TRANSCEIVER_RESP = 0x80000009L;
    static final long DELIVER_SM = 0x00000005L;
    static final long DELIVER_SM_RESP = 0x80000005L;
    static final long ENQUIRE_LINK = 0x00000015L;
    static final long ENQUIRE_LINK_RESP = 0x80000015L;
    static final long UNBIND = 0x00000006L;
    static final long UNBIND_RESP = 0x80000006L;
    static final long GENERIC_NACK = 0x80000000L;

    private SmppPdus() {}

    public static byte[] bindRequest(long sequence) {
        return bindRequest(sequence, "system", "password");
    }

    static byte[] bindRequest(long sequence, String systemId, String password) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        cOctet(body, systemId);
        cOctet(body, password);
        cOctet(body, "");
        body.write(0x34);
        body.write(0);
        body.write(0);
        cOctet(body, "");
        return pdu(BIND_TRANSCEIVER, 0, sequence, body.toByteArray());
    }

    public static byte[] bindResponse(long sequence, long status) {
        return pdu(
            BIND_TRANSCEIVER_RESP,
            status,
            sequence,
            status == 0 ? cOctet("smscsim") : new byte[0]
        );
    }

    public static byte[] deliver(long sequence, String content) {
        return deliver(
            sequence,
            "111111111111",
            "22222",
            content.getBytes(StandardCharsets.UTF_16BE),
            8,
            0,
            new byte[0]
        );
    }

    static byte[] deliver(
        long sequence,
        String source,
        String destination,
        byte[] message,
        int dataCoding,
        int esmClass,
        byte[] optionalParameters
    ) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        cOctet(body, "");
        body.write(0);
        body.write(0);
        cOctet(body, source);
        body.write(0);
        body.write(0);
        cOctet(body, destination);
        body.write(esmClass);
        body.write(0);
        body.write(0);
        cOctet(body, "");
        cOctet(body, "");
        body.write(0);
        body.write(0);
        body.write(dataCoding);
        body.write(0);
        body.write(message.length);
        body.writeBytes(message);
        body.writeBytes(optionalParameters);
        return pdu(DELIVER_SM, 0, sequence, body.toByteArray());
    }

    public static byte[] deliverResponse(long sequence, long status) {
        return pdu(DELIVER_SM_RESP, status, sequence, new byte[] {0});
    }

    static byte[] enquireLink(long sequence) {
        return pdu(ENQUIRE_LINK, 0, sequence, new byte[0]);
    }

    static byte[] enquireLinkResponse(long sequence) {
        return pdu(ENQUIRE_LINK_RESP, 0, sequence, new byte[0]);
    }

    static byte[] unbind(long sequence) {
        return pdu(UNBIND, 0, sequence, new byte[0]);
    }

    static byte[] unbindResponse(long sequence) {
        return pdu(UNBIND_RESP, 0, sequence, new byte[0]);
    }

    static byte[] pdu(long commandId, long status, long sequence, byte[] body) {
        return ByteBuffer.allocate(16 + body.length)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(16 + body.length)
            .putInt((int) commandId)
            .putInt((int) status)
            .putInt((int) sequence)
            .put(body)
            .array();
    }

    static byte[] header(long commandLength, long commandId, long status, long sequence) {
        return ByteBuffer.allocate(16)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt((int) commandLength)
            .putInt((int) commandId)
            .putInt((int) status)
            .putInt((int) sequence)
            .array();
    }

    static byte[] tlv(int tag, byte[] value) {
        return ByteBuffer.allocate(4 + value.length)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort((short) tag)
            .putShort((short) value.length)
            .put(value)
            .array();
    }

    static byte[] concat(byte[]... values) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (byte[] value : values) {
            result.writeBytes(value);
        }
        return result.toByteArray();
    }

    private static byte[] cOctet(String value) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        cOctet(result, value);
        return result.toByteArray();
    }

    private static void cOctet(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
        output.write(0);
    }
}
