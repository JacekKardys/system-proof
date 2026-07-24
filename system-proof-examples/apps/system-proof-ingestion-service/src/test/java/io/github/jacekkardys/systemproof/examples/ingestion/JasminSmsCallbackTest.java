package io.github.jacekkardys.systemproof.examples.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

class JasminSmsCallbackTest {

    @Test
    void decodesJasminUcs2BinaryContentAtTheHttpBoundary() {
        String expectedContent = "SYSTEM-PROOF-PERSISTENCE-123";
        var form = new LinkedMultiValueMap<String, String>();
        form.add("id", "jasmin-message-1");
        form.add("from", "48111000111");
        form.add("to", "99001");
        form.add("content", "\0S\0Y\0S");
        form.add("binary", HexFormat.of().formatHex(expectedContent.getBytes(StandardCharsets.UTF_16BE)));
        form.add("coding", "\u0008");

        SmsIngestionCommand command = JasminSmsCallback.from(form).toCommand();

        assertThat(command.externalMessageId()).isEqualTo("jasmin-message-1");
        assertThat(command.sourceAddress()).isEqualTo("48111000111");
        assertThat(command.destinationAddress()).isEqualTo("99001");
        assertThat(command.content()).isEqualTo(expectedContent);
    }

    @Test
    void preservesTextContentAndAcceptsAddressAliases() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("id", "jasmin-message-2");
        form.add("source_addr", "48111000111");
        form.add("destination_addr", "99001");
        form.add("content", "plain text");
        form.add("coding", "0");

        SmsIngestionCommand command = JasminSmsCallback.from(form).toCommand();

        assertThat(command.sourceAddress()).isEqualTo("48111000111");
        assertThat(command.destinationAddress()).isEqualTo("99001");
        assertThat(command.content()).isEqualTo("plain text");
    }
}
