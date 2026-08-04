/**
 * Provides protocol adapters and the observe-before-forward TCP interaction gateway.
 *
 * <p>Each physical session owns two independent sequential directional pumps. A pump records and
 * correlates one complete protocol unit before waiting on its forwarding permit; while held it
 * retains the adapter-preserved original bytes and performs no further socket reads. Later bytes in
 * that direction remain ordered under bounded buffering and TCP backpressure, while other sessions,
 * connections, and the opposite direction continue on their own tasks.
 *
 * <p>For one held direction, gateway-owned raw-byte arrays are bounded by
 * {@code maximumBufferedBytes + 2 * maximumFrameBytes + min(8192,
 * maximumBufferedBytes)}: the directional buffer, the {@link
 * io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolUnit} copy, the exact write copy,
 * and the fixed read chunk. This excludes array headers and typed evidence owned by an adapter. No
 * unbounded hold queue exists. The gateway performs one write/flush attempt after authorization and
 * reports its result; transport failure is not retried and may have partially delivered bytes.
 */
package io.github.jacekkardys.systemproof.testcontainers.gateway;
