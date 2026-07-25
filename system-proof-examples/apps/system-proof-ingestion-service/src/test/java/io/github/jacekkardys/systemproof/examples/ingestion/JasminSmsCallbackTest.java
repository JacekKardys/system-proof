package io.github.jacekkardys.systemproof.examples.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.server.ResponseStatusException;

class JasminSmsCallbackTest {

    @Test
    void decodesJasminUcs2BinaryContentAtTheHttpBoundary() {
        String expectedContent = "SYSTEM-PROOF-PERSISTENCE-123";
        var form = requiredForm();
        form.add("binary", HexFormat.of().formatHex(expectedContent.getBytes(StandardCharsets.UTF_16BE)));
        form.add("coding", "8");

        SmsIngestionCommand command = JasminSmsCallback.from(form).toCommand();

        assertThat(command.externalMessageId()).isEqualTo("jasmin-message-1");
        assertThat(command.sourceAddress()).isEqualTo("48111000111");
        assertThat(command.destinationAddress()).isEqualTo("99001");
        assertThat(command.content()).isEqualTo(expectedContent);
    }

    @Test
    void decodesTheSingleByteCodingValueSentByJasmin() {
        var form = requiredForm();
        form.add("binary", "0053004d0053");
        form.add("coding", "\u0008");

        assertThat(JasminSmsCallback.from(form).content()).isEqualTo("SMS");
    }

    @Test
    void preservesWhitespaceOnlyTextContentAndAcceptsAddressAliases() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("id", "jasmin-message-2");
        form.add("source_addr", "48111000111");
        form.add("destination_addr", "99001");
        form.add("content", "   ");
        form.add("coding", "0");

        SmsIngestionCommand command = JasminSmsCallback.from(form).toCommand();

        assertThat(command.sourceAddress()).isEqualTo("48111000111");
        assertThat(command.destinationAddress()).isEqualTo("99001");
        assertThat(command.content()).isEqualTo("   ");
    }

    @Test
    void rejectsUcs2CallbackWithoutBinaryContent() {
        var form = requiredForm();
        form.add("content", "must not be used");
        form.add("coding", "8");

        assertBadRequest(form, "binary");
    }

    @Test
    void rejectsTextCallbackWithoutTextContent() {
        var form = requiredForm();
        form.add("binary", "0074006500780074");
        form.add("coding", "0");

        assertBadRequest(form, "content");
    }

    @Test
    void rejectsInvalidUcs2BinaryContent() {
        var form = requiredForm();
        form.add("binary", "not-hexadecimal");
        form.add("coding", "8");

        assertThatThrownBy(() -> JasminSmsCallback.from(form))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).contains("not valid hexadecimal");
            });
    }

    @Test
    void rejectsIncompleteUcs2CodeUnit() {
        var form = requiredForm();
        form.add("binary", "00");
        form.add("coding", "8");

        assertThatThrownBy(() -> JasminSmsCallback.from(form))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).contains("complete two-byte code units");
            });
    }

    private static LinkedMultiValueMap<String, String> requiredForm() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("id", "jasmin-message-1");
        form.add("from", "48111000111");
        form.add("to", "99001");
        return form;
    }

    private static void assertBadRequest(LinkedMultiValueMap<String, String> form, String missingField) {
        assertThatThrownBy(() -> JasminSmsCallback.from(form))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).contains(missingField);
            });
    }
}
