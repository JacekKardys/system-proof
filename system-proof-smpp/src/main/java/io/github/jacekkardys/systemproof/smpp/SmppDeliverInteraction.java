package io.github.jacekkardys.systemproof.smpp;

/**
 * Ephemeral semantic view of one characterized deliver_sm PDU.
 * Every view and character accessor expires when the correlation callback returns.
 */
public interface SmppDeliverInteraction {
    int esmClass();

    int dataCoding();

    Characters sourceAddress();

    Characters destinationAddress();

    Characters message();

    /** Read-only character access whose every operation checks callback activity. */
    interface Characters {
        int length();

        char charAt(int index);

        void copyTo(int sourceOffset, char[] destination, int destinationOffset, int length);
    }
}
