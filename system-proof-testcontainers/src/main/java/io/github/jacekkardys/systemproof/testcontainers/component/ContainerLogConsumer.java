package io.github.jacekkardys.systemproof.testcontainers.component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.containers.output.OutputFrame;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.journal.LogLevel;
import io.github.jacekkardys.systemproof.journal.RedactedDiagnosticText;

/** Applies explicit bounded policies to container output without a raw default path. */
final class ContainerLogConsumer implements Consumer<OutputFrame> {
    private static final int MAX_SANITIZER_INPUT_BYTES = 16 * 1024;
    private static final Pattern LEVEL_MARKER = Pattern.compile(
        "(?i)(?:\\\"(?:level|severity)\\\"\\s*:\\s*\\\"|(?:^|[\\s\\[(:]))"
            + "(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|NOTICE|LOG)"
            + "(?:\\\"|$|[\\s\\]):])"
    );

    private final DriverContext context;
    private final Component component;
    private final RedactedDiagnosticText.Sanitizer sanitizer;

    ContainerLogConsumer(
        DriverContext context,
        Component component,
        RedactedDiagnosticText.Sanitizer sanitizer
    ) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.component = Objects.requireNonNull(component, "component must not be null");
        this.sanitizer = sanitizer;
    }

    @Override
    public void accept(OutputFrame frame) {
        if (frame == null || frame.getType() == OutputFrame.OutputType.END) {
            return;
        }
        byte[] bytes = frame.getBytes();
        if (bytes == null || bytes.length == 0) {
            return;
        }
        if (sanitizer == null) {
            return;
        }
        int consideredBytes = Math.min(bytes.length, MAX_SANITIZER_INPUT_BYTES);
        String boundedOutput = new String(
            bytes,
            0,
            consideredBytes,
            StandardCharsets.UTF_8
        ).stripTrailing();
        if (boundedOutput.isBlank()) {
            return;
        }
        LogLevel level = detectLevel(frame.getType(), boundedOutput);
        context.log(
            component,
            level,
            RedactedDiagnosticText.redact(boundedOutput, sanitizer)
        );
    }

    static LogLevel detectLevel(OutputFrame.OutputType outputType, String output) {
        Matcher marker = LEVEL_MARKER.matcher(output);
        if (marker.find()) {
            return switch (marker.group(1).toUpperCase(Locale.ROOT)) {
                case "TRACE" -> LogLevel.TRACE;
                case "DEBUG" -> LogLevel.DEBUG;
                case "WARN", "WARNING" -> LogLevel.WARN;
                case "ERROR", "FATAL" -> LogLevel.ERROR;
                default -> LogLevel.INFO;
            };
        }
        return outputType == OutputFrame.OutputType.STDERR ? LogLevel.ERROR : LogLevel.INFO;
    }

}
