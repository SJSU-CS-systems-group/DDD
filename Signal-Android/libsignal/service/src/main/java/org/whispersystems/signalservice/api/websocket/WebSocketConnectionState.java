package org.whispersystems.signalservice.api.websocket;

/**
 * Represent the state of a single WebSocketConnection.
 */
public enum WebSocketConnectionState {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
  RECONNECTING,
  DISCONNECTING,
  AUTHENTICATION_FAILED,
  FAILED;

  public boolean isFailure() {
    return this == AUTHENTICATION_FAILED || this == FAILED;
  }
}
