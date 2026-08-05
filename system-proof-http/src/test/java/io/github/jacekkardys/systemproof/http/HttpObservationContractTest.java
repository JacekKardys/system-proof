package io.github.jacekkardys.systemproof.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Capability;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolObservationContract;

class HttpObservationContractTest {

    @Test
    void shouldDeclareOnlyCompleteUnitSemanticControlWithoutCorrelationPolicy() {
        ProtocolObservationContract contract = new HttpProtocolAdapter()
            .observationContract()
            .orElseThrow();

        assertThat(contract.protocolId()).isEqualTo("http");
        assertThat(contract.protocolScheme()).isEqualTo("http");
        assertThat(contract.endpointType()).isEqualTo(URI.class);
        assertThat(contract.evidenceSchema())
            .isEqualTo(new HttpProtocolAdapter().evidenceCodec().schemaId());
        assertThat(contract.nativeFlowReferenceSchema()).isEmpty();
        assertThat(contract.capabilities()).containsExactly(Capability.SEMANTIC_CONTROL);
        assertThat(contract.supportedFeatures()).isEmpty();
    }

    @Test
    void shouldAddOnlyCorrelationCapabilityWhenPolicyIsConfigured() {
        ProtocolObservationContract contract = new HttpProtocolAdapter(
            HttpRequestCorrelation.none()
        ).observationContract().orElseThrow();

        assertThat(contract.nativeFlowReferenceSchema())
            .contains(HttpExchangeRef.codec().schemaId());
        assertThat(contract.capabilities()).containsExactlyInAnyOrder(
            Capability.CORRELATION_CONTRIBUTIONS,
            Capability.SEMANTIC_CONTROL
        );
        assertThat(contract.supportedFeatures()).isEmpty();
    }
}
