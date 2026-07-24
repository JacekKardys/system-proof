package io.github.jacekkardys.systemproof.examples.smsc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.jsmpp.bean.AlertNotification;
import org.jsmpp.bean.BindType;
import org.jsmpp.bean.DataSm;
import org.jsmpp.bean.DeliverSm;
import org.jsmpp.bean.NumberingPlanIndicator;
import org.jsmpp.bean.TypeOfNumber;
import org.jsmpp.session.BindParameter;
import org.jsmpp.session.DataSmResult;
import org.jsmpp.session.MessageReceiverListener;
import org.jsmpp.session.SMPPSession;
import org.jsmpp.session.Session;
import org.junit.jupiter.api.Test;

class SmscSimulatorTest {
    @Test
    void correlatesRealDeliverSmResponseBySessionAndRequestedSequence() throws Exception {
        int port = freePort();
        SmscSimulator simulator = new SmscSimulator(port, "sp-test", "password");
        SMPPSession client = new SMPPSession();
        CountDownLatch received = new CountDownLatch(1);
        byte[][] payload = new byte[1][];
        client.setMessageReceiverListener(new MessageReceiverListener() {
            @Override
            public void onAcceptDeliverSm(DeliverSm deliverSm) {
                payload[0] = deliverSm.getShortMessage();
                received.countDown();
            }

            @Override
            public void onAcceptAlertNotification(AlertNotification alertNotification) {
            }

            @Override
            public DataSmResult onAcceptDataSm(DataSm dataSm, Session source) {
                return null;
            }
        });

        try {
            simulator.start();
            client.connectAndBind("127.0.0.1", port, new BindParameter(
                BindType.BIND_TRX,
                "sp-test",
                "password",
                "",
                TypeOfNumber.UNKNOWN,
                NumberingPlanIndicator.UNKNOWN,
                ""
            ));
            var dispatch = simulator.send(new SmsTestMessage(
                "TEST-1",
                "999000000001",
                "99001",
                "HELLO".getBytes(StandardCharsets.US_ASCII),
                (byte) 0,
                (byte) 0,
                (byte) 0,
                null,
                (byte) 0,
                Map.of(),
                101
            ));

            assertThat(dispatch.sequenceNumber()).isEqualTo(101);
            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(simulator.events("TEST-1"))
                    .extracting(SmscEvent::sessionId)
                    .containsOnly(dispatch.sessionId())
            );
            assertThat(simulator.events("TEST-1"))
                .extracting(SmscEvent::eventType)
                .containsExactly(SmscEventType.DELIVER_SM_SENT, SmscEventType.DELIVER_SM_RESP_RECEIVED);
            assertThat(simulator.events("TEST-1"))
                .extracting(SmscEvent::sequenceNumber)
                .containsOnly(101);
            assertThat(payload[0]).isEqualTo("HELLO".getBytes(StandardCharsets.US_ASCII));
        } finally {
            client.close();
            simulator.close();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
