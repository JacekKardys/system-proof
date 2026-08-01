package io.github.jacekkardys.systemproof.junit.internal;

import io.github.jacekkardys.systemproof.junit.annotation.SystemProof;
import java.util.Objects;
import lombok.val;
import org.junit.jupiter.api.DisplayNameGenerator;

/** Uses optional System Proof scenario metadata when JUnit discovers a test class. */
public final class SystemProofDisplayNameGenerator extends DisplayNameGenerator.Standard {

    @Override
    public String generateDisplayNameForClass(Class<?> testClass) {
        Objects.requireNonNull(testClass, "testClass must not be null");
        val declaration = testClass.getAnnotation(SystemProof.class);
        if (declaration == null) {
            return super.generateDisplayNameForClass(testClass);
        }

        val title = declaration.title().strip();
        return title.isEmpty() ? super.generateDisplayNameForClass(testClass) : title;
    }
}
