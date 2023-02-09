package org.whispersystems.signalservice.internal.push.exceptions;

import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;

public final class GroupNotFoundException extends NonSuccessfulResponseCodeException {
  public GroupNotFoundException() {
    super(404);
  }
}
