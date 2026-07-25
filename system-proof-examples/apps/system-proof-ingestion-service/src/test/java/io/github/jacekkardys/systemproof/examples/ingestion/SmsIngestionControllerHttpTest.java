package io.github.jacekkardys.systemproof.examples.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.util.LinkedMultiValueMap;

@SpringBootTest(
    classes = SystemProofIngestionApplication.class,
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
        "management.endpoint.health.group.readiness.include=readinessState",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
    }
)
class SmsIngestionControllerHttpTest {
    private static final String ENDPOINT = "/v1/ingestion/sms";

    @Autowired
    TestRestTemplate http;

    @MockitoBean
    SmsIngestionService service;

    @Test
    void returnsTheExactJasminAcknowledgementAfterSuccessfulIngestion() {
        var response = postSms();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().getType()).isEqualTo("text");
        assertThat(response.getHeaders().getContentType().getSubtype()).isEqualTo("plain");
        assertThat(response.getBody()).isEqualTo("ACK/Jasmin");
    }

    @Test
    void doesNotAcknowledgeWhenTransactionalIngestionFails() {
        doThrow(new TransactionSystemException("transactional write failed"))
            .when(service)
            .ingest(any());

        var response = postSms();

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        assertThat(response.getBody()).doesNotContain("ACK/Jasmin");
    }

    private org.springframework.http.ResponseEntity<String> postSms() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("id", "jasmin-message-1");
        form.add("from", "48111000111");
        form.add("to", "99001");
        form.add("content", "test message");
        form.add("coding", "0");
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return http.exchange(
            ENDPOINT,
            HttpMethod.POST,
            new HttpEntity<>(form, headers),
            String.class
        );
    }
}
