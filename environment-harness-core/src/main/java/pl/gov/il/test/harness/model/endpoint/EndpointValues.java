package pl.gov.il.test.harness.model.endpoint;

final class EndpointValues {
    private EndpointValues() {}

    static String requireText(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
        return value;
    }

    static int requirePort(int value, String description) {
        if (value < 1 || value > 65_535) {
            throw new IllegalArgumentException(description + " must be between 1 and 65535");
        }
        return value;
    }
}
