package io.github.jacekkardys.systemproof.examples.sms.environment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Future;
import org.testcontainers.images.builder.ImageFromDockerfile;

public final class ReferenceImages {
    public static final String INGESTION = "system-proof-ingestion-service:local";
    public static final String SMSC = "system-proof-ukarim-smscsim:local";
    private static final String INGESTION_JAR_PROPERTY = "system.proof.ingestion.jar";

    private ReferenceImages() {
    }

    public static Future<String> ingestion() {
        Path applicationDirectory = repositoryRoot()
            .resolve("system-proof-examples")
            .resolve("apps")
            .resolve("system-proof-ingestion-service");
        Path applicationJar = Path.of(System.getProperty(
            INGESTION_JAR_PROPERTY,
            repositoryRoot()
                .resolve("system-proof-examples")
                .resolve("target")
                .resolve("reference-images")
                .resolve("system-proof-ingestion-service.jar")
                .toString()
        )).toAbsolutePath().normalize();
        if (!Files.isRegularFile(applicationJar)) {
            throw new IllegalStateException(
                "Maven-resolved reference application JAR is missing: " + applicationJar
            );
        }
        return new ImageFromDockerfile(INGESTION, false)
            .withFileFromPath("Dockerfile", applicationDirectory.resolve("Dockerfile"))
            .withFileFromPath("app.jar", applicationJar);
    }

    public static Future<String> smsc() {
        Path fixtureDirectory = repositoryRoot()
            .resolve("system-proof-examples")
            .resolve("fixtures")
            .resolve("ukarim-smscsim");
        return new ImageFromDockerfile(SMSC, false)
            .withFileFromPath("Dockerfile", fixtureDirectory.resolve("Dockerfile"))
            .withFileFromPath(
                "patches/0001-empty-deliver-sm-service-type.patch",
                fixtureDirectory.resolve("patches").resolve("0001-empty-deliver-sm-service-type.patch")
            )
            .withFileFromPath("service_type_test.go", fixtureDirectory.resolve("service_type_test.go"));
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
