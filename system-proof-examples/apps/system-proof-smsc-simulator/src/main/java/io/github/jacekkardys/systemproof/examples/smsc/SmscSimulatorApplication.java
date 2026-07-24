package io.github.jacekkardys.systemproof.examples.smsc;

public final class SmscSimulatorApplication {
    private SmscSimulatorApplication() {
    }

    public static void main(String[] args) throws Exception {
        int smppPort = integerEnvironment("SMSC_SMPP_PORT", 2775);
        int controlPort = integerEnvironment("SMSC_CONTROL_PORT", 8081);
        String systemId = requiredEnvironment("SMSC_SYSTEM_ID");
        String password = requiredEnvironment("SMSC_PASSWORD");
        SmscSimulator simulator = new SmscSimulator(smppPort, systemId, password);
        SmscControlServer control = new SmscControlServer(controlPort, simulator);
        simulator.start();
        control.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            control.close();
            simulator.close();
        }));
    }

    private static int integerEnvironment(String name, int defaultValue) {
        String value = System.getenv(name);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
