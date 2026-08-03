package io.github.jacekkardys.systemproof.diagnostics;

/** Minimum severity emitted for one environment event source; diagnostics retain every event. */
public enum LogLevel {
    OFF(Integer.MAX_VALUE),
    ERROR(50),
    WARN(40),
    INFO(30),
    DEBUG(20),
    TRACE(10);

    private final int severity;

    LogLevel(int severity) {
        this.severity = severity;
    }

    public boolean includes(LogLevel eventLevel) {
        return this != OFF && eventLevel != OFF && eventLevel.severity >= severity;
    }
}
