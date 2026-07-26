package io.github.jacekkardys.systemproof.testcontainers.gateway;

import java.util.Objects;

/** Result of decoding the bounded, currently unforwarded bytes for one direction. */
public sealed interface ProtocolDecodeResult<E>
    permits ProtocolDecodeResult.NeedMoreData, ProtocolDecodeResult.Complete {

    static <E> ProtocolDecodeResult<E> needMoreData() {
        return new NeedMoreData<>();
    }

    static <E> ProtocolDecodeResult<E> complete(ProtocolUnit<E> unit) {
        return new Complete<>(unit);
    }

    /** No complete forwarding unit is available yet. */
    record NeedMoreData<E>() implements ProtocolDecodeResult<E> {}

    /** Exactly one complete forwarding unit starts at the current buffer position. */
    record Complete<E>(ProtocolUnit<E> unit) implements ProtocolDecodeResult<E> {
        public Complete {
            unit = Objects.requireNonNull(unit, "unit must not be null");
        }
    }
}
