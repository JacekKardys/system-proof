package pl.gov.il.test.harness.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import pl.gov.il.test.harness.diagnostics.EnvironmentDiagnostics;
import pl.gov.il.test.harness.diagnostics.EnvironmentEventLog;
import pl.gov.il.test.harness.driver.DiagnosticSource;
import pl.gov.il.test.harness.model.AbstractComponent;
import pl.gov.il.test.harness.model.Component;
import pl.gov.il.test.harness.model.ComponentState;
import pl.gov.il.test.harness.model.EnvironmentState;

/** Captures lifecycle events and component-owned diagnostic sources without running cleanup. */
final class RuntimeDiagnostics {
    private final EnvironmentEventLog eventLog;
    private final List<OwnedDiagnosticSource> sources = new ArrayList<>();

    RuntimeDiagnostics(EnvironmentEventLog eventLog) {
        this.eventLog = eventLog;
    }

    EnvironmentEventLog eventLog() {
        return eventLog;
    }

    void add(Component component, List<DiagnosticSource> diagnostics) {
        diagnostics.forEach(source -> sources.add(new OwnedDiagnosticSource(component, source)));
    }

    EnvironmentDiagnostics capture(
        EnvironmentState environmentState,
        List<AbstractComponent<?, ?>> components,
        Function<Component, ComponentState> componentState
    ) {
        StringBuilder content = new StringBuilder();
        content.append("[STATE] environment=").append(environmentState);
        for (AbstractComponent<?, ?> component : components) {
            content.append(System.lineSeparator())
                .append("[STATE] component=").append(component.id())
                .append(" type=").append(component.type())
                .append(" state=").append(componentState.apply(component));
        }
        if (!eventLog.snapshot().content().isBlank()) {
            content.append(System.lineSeparator()).append(eventLog.snapshot().content());
        }
        for (OwnedDiagnosticSource owned : sources) {
            String captured;
            try {
                captured = owned.source().content().get();
            } catch (RuntimeException failure) {
                captured = "Diagnostic capture failed: " + failure;
            }
            if (captured != null && !captured.isBlank()) {
                content.append(System.lineSeparator())
                    .append("[DIAGNOSTIC] [").append(owned.component().id()).append("] [")
                    .append(owned.source().name()).append("]")
                    .append(System.lineSeparator()).append(captured);
            }
        }
        return EnvironmentDiagnostics.diagnostics(content.toString());
    }

    private record OwnedDiagnosticSource(Component component, DiagnosticSource source) {}
}
