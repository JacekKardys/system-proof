package io.github.jacekkardys.systemproof.smpp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.endpoint.SmppEndpoint;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Capability;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolObservationContract;

class SmppObservationContractTest {

    @Test
    void shouldDeclareOnlySemanticControlWithoutCorrelationPolicy() {
        ProtocolObservationContract contract = new SmppProtocolAdapter()
            .observationContract()
            .orElseThrow();

        assertThat(contract.protocolId()).isEqualTo("smpp");
        assertThat(contract.protocolScheme()).isEqualTo("smpp");
        assertThat(contract.endpointType()).isEqualTo(SmppEndpoint.class);
        assertThat(contract.evidenceSchema())
            .isEqualTo(new SmppProtocolAdapter().evidenceCodec().schemaId());
        assertThat(contract.nativeFlowReferenceSchema()).isEmpty();
        assertThat(contract.capabilities()).containsExactly(Capability.SEMANTIC_CONTROL);
        assertThat(contract.supportedFeatures()).isEmpty();
    }

    @Test
    void shouldAddOnlyCorrelationCapabilityWhenPolicyIsConfigured() {
        ProtocolObservationContract contract = new SmppProtocolAdapter(
            SmppDeliverCorrelation.none()
        ).observationContract().orElseThrow();

        assertThat(contract.nativeFlowReferenceSchema())
            .contains(SmppExchangeRef.codec().schemaId());
        assertThat(contract.capabilities()).containsExactlyInAnyOrder(
            Capability.CORRELATION_CONTRIBUTIONS,
            Capability.SEMANTIC_CONTROL
        );
        assertThat(contract.supportedFeatures()).isEmpty();
    }
}
