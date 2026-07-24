package io.github.jacekkardys.systemproof.examples.ingestion;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;

record JasminSmsCallback(
    String externalMessageId,
    String sourceAddress,
    String destinationAddress,
    String content
) {
    static JasminSmsCallback from(MultiValueMap<String, String> form) {
        return new JasminSmsCallback(
            required(form, "id"),
            requiredAlias(form, "from", "source_addr"),
            requiredAlias(form, "to", "destination_addr"),
            content(form)
        );
    }

    SmsIngestionCommand toCommand() {
        return new SmsIngestionCommand(
            externalMessageId,
            sourceAddress,
            destinationAddress,
            content
        );
    }

    private static String content(MultiValueMap<String, String> form) {
        String content = required(form, "content");
        if (!isUcs2(form.getFirst("coding"))) {
            return content;
        }

        String binary = required(form, "binary");
        try {
            byte[] bytes = HexFormat.of().parseHex(binary);
            if (bytes.length % 2 != 0) {
                throw badRequest("UCS2 binary content must contain complete two-byte code units");
            }
            return new String(bytes, StandardCharsets.UTF_16BE);
        } catch (IllegalArgumentException exception) {
            throw badRequest("UCS2 binary content is not valid hexadecimal", exception);
        }
    }

    private static boolean isUcs2(String coding) {
        return "8".equals(coding) || (coding != null && coding.length() == 1 && coding.charAt(0) == 8);
    }

    private static String requiredAlias(MultiValueMap<String, String> form, String primary, String alias) {
        return firstText(form, List.of(primary, alias));
    }

    private static String required(MultiValueMap<String, String> form, String name) {
        return firstText(form, List.of(name));
    }

    private static String firstText(MultiValueMap<String, String> form, List<String> names) {
        return names.stream()
            .map(form::getFirst)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElseThrow(() -> badRequest(
                "Required form field is missing: " + String.join(" or ", names)
            ));
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(BAD_REQUEST, reason);
    }

    private static ResponseStatusException badRequest(String reason, Exception cause) {
        return new ResponseStatusException(BAD_REQUEST, reason, cause);
    }
}
