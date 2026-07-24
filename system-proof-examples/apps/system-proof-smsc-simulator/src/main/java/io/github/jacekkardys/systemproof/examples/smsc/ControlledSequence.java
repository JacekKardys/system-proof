package io.github.jacekkardys.systemproof.examples.smsc;

import java.util.concurrent.atomic.AtomicInteger;
import org.jsmpp.util.Sequence;

final class ControlledSequence extends Sequence {
    private final ThreadLocal<Integer> requested = new ThreadLocal<>();
    private final AtomicInteger fallback = new AtomicInteger();

    ControlledSequence() {
        super(0);
    }

    void request(Integer value) {
        if (value == null) {
            requested.remove();
        } else {
            requested.set(value);
        }
    }

    void clearRequest() {
        requested.remove();
    }

    @Override
    public synchronized int nextValue() {
        Integer explicit = requested.get();
        if (explicit != null) {
            fallback.accumulateAndGet(explicit, Math::max);
            return explicit;
        }
        return fallback.updateAndGet(current -> current == Integer.MAX_VALUE ? 1 : current + 1);
    }

    @Override
    public synchronized int currentValue() {
        return fallback.get();
    }
}
