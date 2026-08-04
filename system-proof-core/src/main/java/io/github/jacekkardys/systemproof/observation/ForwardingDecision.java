package io.github.jacekkardys.systemproof.observation;

/** Decision returned after one complete interaction has been recorded. */
public enum ForwardingDecision {
    /** Forward the exact original bytes immediately. */
    FORWARD,

    /** Do not forward any retained byte and close the affected physical session. */
    CLOSE_SESSION
}
