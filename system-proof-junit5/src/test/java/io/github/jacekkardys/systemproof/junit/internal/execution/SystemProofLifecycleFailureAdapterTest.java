package io.github.jacekkardys.systemproof.junit.internal.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
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
        val context = contextWith(null);

        val outcome = adapter.execute(
            context,
            () -> {
                throw closeFailure;
            }
        );

        assertThat(context.removeLifecycleFailure()).isSameAs(closeFailure);
        assertThatThrownBy(outcome::propagateIfPrimary).isSameAs(closeFailure);
    }

    @Test
    void shouldSuppressALifecycleFailureWhenJUnitAlreadyHasAPrimaryFailure() {
        IllegalStateException testFailure = new IllegalStateException("test exploded");
        IllegalStateException closeFailure = new IllegalStateException("close exploded");
        val context = contextWith(testFailure);

        val outcome = adapter.execute(
            context,
            () -> {
                throw closeFailure;
            }
        );

        assertThat(context.removeLifecycleFailure()).isSameAs(closeFailure);
        assertThat(testFailure.getSuppressed()).containsExactly(closeFailure);
        assertThatCode(outcome::propagateIfPrimary).doesNotThrowAnyException();
    }

    @Test
    void shouldPreserveAnErrorAsThePrimaryLifecycleFailure() {
        AssertionError closeFailure = new AssertionError("close exploded");
        val context = contextWith(null);

        val outcome = adapter.execute(
            context,
            () -> {
                throw closeFailure;
            }
        );

        assertThatThrownBy(outcome::propagateIfPrimary).isSameAs(closeFailure);
    }

    @Test
    void shouldRepresentSuccessfulLifecycleExecution() {
        val context = contextWith(null);
        val outcome = adapter.execute(
            context,
            () -> {}
        );

        assertThat(context.removeLifecycleFailure()).isNull();
        assertThatCode(outcome::propagateIfPrimary).doesNotThrowAnyException();
    }

    private static SystemProofSharedContext contextWith(Throwable primaryFailure) {
        Map<Object, Object> values = new HashMap<>();
        val store = (ExtensionContext.Store) Proxy.newProxyInstance(
            ExtensionContext.Store.class.getClassLoader(),
            new Class<?>[] { ExtensionContext.Store.class },
            (proxy, method, arguments) -> switch (method.getName()) {
                case "put" -> values.put(arguments[0], arguments[1]);
                case "get" -> values.get(arguments[0]);
                case "remove" -> values.remove(arguments[0]);
                case "toString" -> "SystemProofLifecycleFailureAdapterTestStore";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
        val context = (ExtensionContext) Proxy.newProxyInstance(
            ExtensionContext.class.getClassLoader(),
            new Class<?>[] { ExtensionContext.class },
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getExecutionException" -> Optional.ofNullable(primaryFailure);
                case "getStore" -> store;
                case "toString" -> "SystemProofLifecycleFailureAdapterTestContext";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
        return SystemProofSharedContext.of(context);
    }
}
