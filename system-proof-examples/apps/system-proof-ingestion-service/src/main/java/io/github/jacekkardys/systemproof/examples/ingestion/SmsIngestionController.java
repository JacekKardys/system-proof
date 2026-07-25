package io.github.jacekkardys.systemproof.examples.ingestion;

import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
        service.ingest(JasminSmsCallback.from(form).toCommand());
        return "ACK/Jasmin";
    }
}
