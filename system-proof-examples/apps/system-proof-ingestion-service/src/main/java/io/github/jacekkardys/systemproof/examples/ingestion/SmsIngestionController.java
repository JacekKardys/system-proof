package io.github.jacekkardys.systemproof.examples.ingestion;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
public class SmsIngestionController {
    private final SmsIngestionService service;

    public SmsIngestionController(SmsIngestionService service) {
        this.service = service;
    }

    @PostMapping(
        path = "/v1/ingestion/sms",
        consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        produces = MediaType.TEXT_PLAIN_VALUE
    )
    public String ingest(@RequestParam MultiValueMap<String, String> form) {
        service.ingest(new SmsIngestionCommand(
            required(form, "id"),
            requiredAlias(form, "from", "source_addr"),
            requiredAlias(form, "to", "destination_addr"),
            required(form, "content")
        ));
        return "ACK/Jasmin";
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
            .orElseThrow(() -> new ResponseStatusException(
                BAD_REQUEST,
                "Required form field is missing: " + String.join(" or ", names)
            ));
    }
}
