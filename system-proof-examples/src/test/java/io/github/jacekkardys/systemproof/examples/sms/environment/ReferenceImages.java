package io.github.jacekkardys.systemproof.examples.sms.environment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Future;
import org.testcontainers.images.builder.ImageFromDockerfile;

public final class ReferenceImages {
    public static final String INGESTION = "system-proof-ingestion-service:local";
    public static final String SMSC = "system-proof-smsc-simulator:local";

    private ReferenceImages() {
    }

    public static Future<String> ingestion() {
        return build(
            INGESTION,
            "system-proof-ingestion-service",
            "system-proof-ingestion-service.jar"
        );
    }

    public static Future<String> smsc() {
        return build(
            SMSC,
            "system-proof-smsc-simulator",
            "system-proof-smsc-simulator.jar"
        );
    }

    private static Future<String> build(String image, String application, String jar) {
        Path applicationDirectory = repositoryRoot()
            .resolve("system-proof-examples")
            .resolve("apps")
            .resolve(application);
        Path applicationJar = applicationDirectory.resolve("target").resolve(jar);
        if (!Files.isRegularFile(applicationJar)) {
            throw new IllegalStateException(
                "Reference application JAR is missing: " + applicationJar
                    + ". Run the examples through the root Maven reactor."
            );
        }
        return new ImageFromDockerfile(image, false)
            .withFileFromPath("Dockerfile", applicationDirectory.resolve("Dockerfile"))
            .withFileFromPath("app.jar", applicationJar);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("mvnw"))
                && Files.isDirectory(current.resolve("system-proof-examples"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate the System Proof repository root");
    }
}
