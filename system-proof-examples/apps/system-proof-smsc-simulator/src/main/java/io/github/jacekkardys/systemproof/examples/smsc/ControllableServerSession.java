package io.github.jacekkardys.systemproof.examples.smsc;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.jsmpp.DefaultPDUReader;
import org.jsmpp.DefaultPDUSender;
import org.jsmpp.InvalidResponseException;
import org.jsmpp.PDUException;
import org.jsmpp.SynchronizedPDUSender;
import org.jsmpp.bean.Command;
import org.jsmpp.bean.ESMClass;
import org.jsmpp.bean.NumberingPlanIndicator;
import org.jsmpp.bean.OptionalParameter;
import org.jsmpp.bean.RawDataCoding;
import org.jsmpp.bean.RegisteredDelivery;
import org.jsmpp.bean.TypeOfNumber;
import org.jsmpp.extra.NegativeResponseException;
import org.jsmpp.extra.ResponseTimeoutException;
import org.jsmpp.session.AbstractSession;
import org.jsmpp.session.SMPPServerSession;
import org.jsmpp.session.SendCommandTask;
import org.jsmpp.session.SessionStateListener;
import org.jsmpp.session.connection.Connection;

final class ControllableServerSession extends SMPPServerSession {
    private final EventJournal journal;
    private final ControlledSequence sequence = new ControlledSequence();
    private final Set<Integer> inFlightSequenceNumbers = ConcurrentHashMap.newKeySet();

    ControllableServerSession(Connection connection, SessionStateListener stateListener, EventJournal journal) {
        super(
            connection,
            stateListener,
            null,
            null,
            4,
            1_000,
            new SynchronizedPDUSender(new DefaultPDUSender()),
            new DefaultPDUReader()
        );
        this.journal = journal;
        installControlledSequence();
        setTransactionTimer(30_000);
        setEnquireLinkTimer(10_000);
    }

    CompletableFuture<MessageDispatch> dispatch(SmsTestMessage message, ExecutorService executor) {
        CompletableFuture<MessageDispatch> written = new CompletableFuture<>();
        executor.submit(() -> sendAndAwaitResponse(message, written));
        return written;
    }

    private void sendAndAwaitResponse(SmsTestMessage message, CompletableFuture<MessageDispatch> written) {
        int[] actualSequence = new int[1];
        sequence.request(message.requestedSequenceNumber());
        try {
            ensureReceivable("deliverShortMessage");
            Command response = executeSendCommand(deliverTask(message, written, actualSequence), getTransactionTimer());
            journal.append(
                SmscEventType.DELIVER_SM_RESP_RECEIVED,
                getSessionId(),
                message.testMessageId(),
                response.getSequenceNumber(),
                response.getCommandStatus(),
                Map.of()
            );
        } catch (NegativeResponseException exception) {
            journal.append(
                SmscEventType.DELIVER_SM_RESP_RECEIVED,
                getSessionId(),
                message.testMessageId(),
                actualSequence[0],
                exception.getCommandStatus(),
                Map.of("result", "negative")
            );
        } catch (PDUException | ResponseTimeoutException | InvalidResponseException | IOException | RuntimeException exception) {
            written.completeExceptionally(exception);
        } finally {
            sequence.clearRequest();
            inFlightSequenceNumbers.remove(actualSequence[0]);
        }
    }

    private SendCommandTask deliverTask(
        SmsTestMessage message,
        CompletableFuture<MessageDispatch> written,
        int[] actualSequence
    ) {
        return new SendCommandTask() {
            @Override
            public void executeTask(OutputStream out, int sequenceNumber) throws PDUException, IOException {
                if (!inFlightSequenceNumbers.add(sequenceNumber)) {
                    throw new IllegalArgumentException("sequence number is already in flight: " + sequenceNumber);
                }
                actualSequence[0] = sequenceNumber;
                pduSender().sendDeliverSm(
                    out,
                    sequenceNumber,
                    "",
                    TypeOfNumber.INTERNATIONAL,
                    NumberingPlanIndicator.ISDN,
                    message.sourceAddress(),
                    TypeOfNumber.NETWORK_SPECIFIC,
                    NumberingPlanIndicator.UNKNOWN,
                    message.destinationAddress(),
                    new ESMClass(message.esmClass()),
                    (byte) 0,
                    message.priorityFlag(),
                    new RegisteredDelivery(message.registeredDelivery()),
                    new RawDataCoding(message.dataCoding()),
                    message.payload(),
                    optionalParameters(message)
                );
                journal.append(
                    SmscEventType.DELIVER_SM_SENT,
                    getSessionId(),
                    message.testMessageId(),
                    sequenceNumber,
                    null,
                    Map.of("payloadBytes", Integer.toString(message.payload().length))
                );
                written.complete(new MessageDispatch(message.testMessageId(), getSessionId(), sequenceNumber));
            }

            @Override
            public String getCommandName() {
                return "deliver_sm";
            }
        };
    }

    private static OptionalParameter[] optionalParameters(SmsTestMessage message) {
        return message.optionalParameters().entrySet().stream()
            .map(entry -> new OptionalParameter.OctetString((short) (entry.getKey() & 0xffff), entry.getValue()))
            .toArray(OptionalParameter[]::new);
    }

    private void installControlledSequence() {
        try {
            Field field = AbstractSession.class.getDeclaredField("sequence");
            field.setAccessible(true);
            field.set(this, sequence);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot install the jSMPP sequence adapter", exception);
        }
    }
}
