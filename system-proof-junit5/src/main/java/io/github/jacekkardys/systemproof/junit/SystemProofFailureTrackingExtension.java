package io.github.jacekkardys.systemproof.junit;

import lombok.SneakyThrows;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

public class SystemProofFailureTrackingExtension implements TestExecutionExceptionHandler {

    @SneakyThrows
    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) {
        SystemProofSharedContext.of(context).putTestFailure(throwable);
        throw throwable;
    }
}
