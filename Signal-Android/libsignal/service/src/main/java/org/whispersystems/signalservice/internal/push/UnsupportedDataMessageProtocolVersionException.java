package org.whispersystems.signalservice.internal.push;


import org.whispersystems.signalservice.api.messages.SignalServiceGroupV2;

import java.util.Optional;

/**
 * Exception that indicates that the data message has a higher required protocol version than the
 * current client is capable of interpreting.
 */
public final class UnsupportedDataMessageProtocolVersionException extends UnsupportedDataMessageException {
    private final int requiredVersion;

    public UnsupportedDataMessageProtocolVersionException(int currentVersion,
                                                          int requiredVersion,
                                                          String sender,
                                                          int senderDevice,
                                                          Optional<SignalServiceGroupV2> group) {
        super("Required version: " + requiredVersion + ", Our version: " + currentVersion, sender, senderDevice, group);
        this.requiredVersion = requiredVersion;
    }

    public int getRequiredVersion() {
        return requiredVersion;
    }
}
