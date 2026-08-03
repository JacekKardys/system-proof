package io.github.jacekkardys.systemproof.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentLogging;
import io.github.jacekkardys.systemproof.driver.DriverResourceKey;

class SharedDriverResourcesTest {
    @Test
    void shouldReuseResourcesAndCloseThemOnceInReverseCreationOrder() {
        List<String> closed = new ArrayList<>();
        AtomicInteger duplicateFactoryCalls = new AtomicInteger();
        DriverResourceKey<RecordingResource> firstKey =
            DriverResourceKey.resourceKey("first", RecordingResource.class);
        DriverResourceKey<RecordingResource> secondKey =
            DriverResourceKey.resourceKey("second", RecordingResource.class);
        SharedDriverResources resources = new SharedDriverResources(new EnvironmentEventPublisher(
            new ScenarioJournal(),
            EnvironmentLogging.defaults()
        ));

        RecordingResource first = resources.getOrCreate(
            firstKey,
            () -> new RecordingResource("first", closed)
        );
        resources.getOrCreate(secondKey, () -> new RecordingResource("second", closed));
        RecordingResource reused = resources.getOrCreate(firstKey, () -> {
            duplicateFactoryCalls.incrementAndGet();
            return new RecordingResource("duplicate", closed);
        });

        assertThat(reused).isSameAs(first);
        assertThat(duplicateFactoryCalls).hasValue(0);
        assertThat(resources.close()).isNull();
        assertThat(resources.close()).isNull();
        assertThat(closed).containsExactly("second", "first");
    }

    private record RecordingResource(String name, List<String> closed) implements AutoCloseable {
        @Override
        public void close() {
            closed.add(name);
        }
    }
}
