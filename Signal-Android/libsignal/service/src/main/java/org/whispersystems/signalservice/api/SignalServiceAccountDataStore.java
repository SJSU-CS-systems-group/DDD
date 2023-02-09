package org.whispersystems.signalservice.api;

import org.signal.libsignal.protocol.state.SignalProtocolStore;

/**
 * And extension of the normal protocol store interface that has additional methods that are needed
 * in the service layer, but not the protocol layer.
 */
public interface SignalServiceAccountDataStore extends SignalProtocolStore, SignalServiceSessionStore, SignalServiceSenderKeyStore {
  /**
   * @return True if the user has linked devices, otherwise false.
   */
  boolean isMultiDevice();
}
