package io.github.jacekkardys.systemproof.junit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.util.Optional;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

class SystemProofLifecycleFailureAdapterTest {

    private final SystemProofLifecycleFailureAdapter adapter =
        new SystemProofLifecycleFailureAdapter();

    @Test
    void shouldPropagateALifecycleFailureWhenJUnitHasNoPrimaryFailure() {
        IllegalStateException closeFailure = new IllegalStateException("close exploded");

        val outcome = adapter.execute(
            contextWith(null),
            () -> {
                throw closeFailure;
            }
        );

        assertThat(outcome.failure()).contains(closeFailure);
        assertThatThrownBy(outcome::propagateIfPrimary).isSameAs(closeFailure);
    }

    @Test
    void shouldSuppressALifecycleFailureWhenJUnitAlreadyHasAPrimaryFailure() {
        IllegalStateException testFailure = new IllegalStateException("test exploded");
        IllegalStateException closeFailure = new IllegalStateException("close exploded");

        val outcome = adapter.execute(
            contextWith(testFailure),
            () -> {
                throw closeFailure;
            }
        );

        assertThat(outcome.failure()).contains(closeFailure);
        assertThat(testFailure.getSuppressed()).containsExactly(closeFailure);
        assertThatCode(outcome::propagateIfPrimary).doesNotThrowAnyException();
    }

    @Test
    void shouldPreserveAnErrorAsThePrimaryLifecycleFailure() {
        AssertionError closeFailure = new AssertionError("close exploded");

        val outcome = adapter.execute(
            contextWith(null),
            () -> {
                throw closeFailure;
            }
        );

        assertThatThrownBy(outcome::propagateIfPrimary).isSameAs(closeFailure);
    }

    @Test
    void shouldRepresentSuccessfulLifecycleExecution() {
        val outcome = adapter.execute(
            contextWith(null),
            () -> {}
        );

        assertThat(outcome.failure()).isEmpty();
        assertThatCode(outcome::propagateIfPrimary).doesNotThrowAnyException();
    }

    private static ExtensionContext contextWith(Throwable primaryFailure) {
        return (ExtensionContext) Proxy.newProxyInstance(
            ExtensionContext.class.getClassLoader(),
            new Class<?>[] { ExtensionContext.class },
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getExecutionException" -> Optional.ofNullable(primaryFailure);
                case "toString" -> "SystemProofLifecycleFailureAdapterTestContext";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }
}
