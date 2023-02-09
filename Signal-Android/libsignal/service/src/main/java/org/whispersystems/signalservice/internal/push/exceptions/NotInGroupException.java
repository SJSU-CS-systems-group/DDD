package org.whispersystems.signalservice.internal.push.exceptions;

import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;

public final class NotInGroupException extends NonSuccessfulResponseCodeException {
  public NotInGroupException() {
    super(403);
  }
}
