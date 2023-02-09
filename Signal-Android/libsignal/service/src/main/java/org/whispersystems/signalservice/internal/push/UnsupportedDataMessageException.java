package org.whispersystems.signalservice.internal.push;


import org.whispersystems.signalservice.api.messages.SignalServiceGroupV2;

import java.util.Optional;

/**
 * Exception that indicates that the data message contains something that is not supported by this
 * version of the application. Subclasses provide more specific information about what data was
 * found that is not supported.
 */
public abstract class UnsupportedDataMessageException extends Exception {

  private final String                         sender;
  private final int                            senderDevice;
  private final Optional<SignalServiceGroupV2> group;

  protected UnsupportedDataMessageException(String message,
                                            String sender,
                                            int senderDevice,
                                            Optional<SignalServiceGroupV2> group)
  {
    super(message);
    this.sender       = sender;
    this.senderDevice = senderDevice;
    this.group        = group;
  }

  public String getSender() {
    return sender;
  }

  public int getSenderDevice() {
    return senderDevice;
  }

  public Optional<SignalServiceGroupV2> getGroup() {
    return group;
  }
}
