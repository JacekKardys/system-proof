package io.github.jacekkardys.systemproof.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.journal.FailureDetails;
import io.github.jacekkardys.systemproof.journal.FailureEvent;

class DriverResourceKeyTest {
    private static final String NAME_CANARY = "driver-resource-name-canary";

    @Test
    void shouldAcceptBoundedPublicResourceIdentifiers() {
        String maximumName = "a".repeat(128);

        DriverResourceKey<TestResource> key = DriverResourceKey.resourceKey(
            maximumName,
            TestResource.class
        );

        assertThat(key.name()).isEqualTo(maximumName);
    }

    @Test
    void shouldRejectHostileResourceNamesAtKeyAndDetachedEventBoundaries() {
        List<String> hostileNames = List.of(
            "",
            " ",
            NAME_CANARY + "\nsecond-line",
            NAME_CANARY + "-zażółć-秘密",
            "a".repeat(129)
        );
        FailureDetails failure = FailureDetails.from(new IllegalStateException("not retained"));

        hostileNames.forEach(name -> {
            assertThatThrownBy(() -> DriverResourceKey.resourceKey(name, TestResource.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name must be 1-128 ASCII identifier characters")
                .hasMessageNotContaining(NAME_CANARY)
                .hasMessageNotContaining("zażółć")
                .hasMessageNotContaining("秘密");
            assertThatThrownBy(() -> new FailureEvent.DriverResourceCleanup(name, failure))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("resourceName must be 1-128 ASCII identifier characters")
                .hasMessageNotContaining(NAME_CANARY)
                .hasMessageNotContaining("zażółć")
                .hasMessageNotContaining("秘密");
        });
    }

    private static final class TestResource implements AutoCloseable {
        @Override
        public void close() {}
    }
}
