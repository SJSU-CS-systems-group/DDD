package org.whispersystems.signalservice.internal.push.exceptions;

import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;

public final class GroupPatchNotAcceptedException extends NonSuccessfulResponseCodeException {
  public GroupPatchNotAcceptedException() {
    super(400);
  }
}
