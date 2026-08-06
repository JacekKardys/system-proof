package io.github.jacekkardys.systemproof.smpp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.BindOutcome;
import io.github.jacekkardys.systemproof.smpp.SmppProtocolSession.SessionModel;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolAdapterException;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolFailureKind;

class SmppSessionModelTest {

    @Test
    void shouldRejectRequestTransitionsAfterConsumerInputEnded() throws Exception {
        assertRequestTransitionsRejectedAfter(FlowDirection.CONSUMER_TO_PROVIDER);
    }

    @Test
    void shouldRejectRequestTransitionsAfterProviderInputEnded() throws Exception {
        assertRequestTransitionsRejectedAfter(FlowDirection.PROVIDER_TO_CONSUMER);
    }

    private static void assertRequestTransitionsRejectedAfter(FlowDirection endedDirection)
        throws Exception {
        assertRejectedWithoutMutation(
            openModel(),
            endedDirection,
            model -> model.bindRequested(11)
        );
        assertRejectedWithoutMutation(
            boundModel(),
            endedDirection,
            model -> model.deliverStarted(12)
        );
        assertRejectedWithoutMutation(
            boundModel(),
            endedDirection,
            model -> model.enquireLinkRequested(13)
        );
        assertRejectedWithoutMutation(
            boundModel(),
            endedDirection,
            model -> model.unbindRequested(14)
        );
    }

    private static void assertRejectedWithoutMutation(
        SessionModel model,
        FlowDirection endedDirection,
        Transition transition
    ) throws Exception {
        model.endOfInput(endedDirection);
        ModelSnapshot before = snapshot(model);

        assertThatThrownBy(() -> transition.apply(model))
            .isInstanceOfSatisfying(ProtocolAdapterException.class, failure ->
                assertThat(failure.kind()).isEqualTo(
                    ProtocolFailureKind.DESYNCHRONIZATION
                )
            );

        assertThat(snapshot(model)).isEqualTo(before);
        model.endOfInput(opposite(endedDirection));
    }

    private static SessionModel openModel() {
        return new SessionModel(1, 4);
    }

    private static SessionModel boundModel() throws ProtocolAdapterException {
        SessionModel model = openModel();
        model.bindRequested(1);
        model.bindResponded(1, BindOutcome.ACCEPTED);
        return model;
    }

    private static FlowDirection opposite(FlowDirection direction) {
        return direction == FlowDirection.CONSUMER_TO_PROVIDER
            ? FlowDirection.PROVIDER_TO_CONSUMER
            : FlowDirection.CONSUMER_TO_PROVIDER;
    }

    @SuppressWarnings("unchecked")
    private static ModelSnapshot snapshot(SessionModel model)
        throws ReflectiveOperationException {
        return new ModelSnapshot(
            value(model, "state").toString(),
            (Long) value(model, "pendingBindSequence"),
            (Long) value(model, "pendingEnquireLinkSequence"),
            (Long) value(model, "pendingUnbindSequence"),
            Map.copyOf((Map<Long, SmppExchangeRef>) value(model, "outstandingDeliveries")),
            (long) value(model, "nextExchangeOrdinal"),
            (boolean) value(model, "consumerInputEnded"),
            (boolean) value(model, "providerInputEnded")
        );
    }

    private static Object value(SessionModel model, String fieldName)
        throws ReflectiveOperationException {
        Field field = SessionModel.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(model);
    }

    @FunctionalInterface
    private interface Transition {
        void apply(SessionModel model) throws ProtocolAdapterException;
    }

    private record ModelSnapshot(
        String state,
        Long pendingBindSequence,
        Long pendingEnquireLinkSequence,
        Long pendingUnbindSequence,
        Map<Long, SmppExchangeRef> outstandingDeliveries,
        long nextExchangeOrdinal,
        boolean consumerInputEnded,
        boolean providerInputEnded
    ) {}
}
