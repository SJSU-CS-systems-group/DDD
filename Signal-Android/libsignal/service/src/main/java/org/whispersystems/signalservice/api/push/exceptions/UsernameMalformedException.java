package org.whispersystems.signalservice.api.push.exceptions;

public class UsernameMalformedException extends NonSuccessfulResponseCodeException {
  public UsernameMalformedException() {
    super(400);
  }
}
