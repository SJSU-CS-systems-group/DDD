/**
 * Copyright (C) 2014-2016 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */

package org.whispersystems.signalservice.api.push.exceptions;


import java.util.Optional;

public class RateLimitException extends NonSuccessfulResponseCodeException {
  private final Optional<Long> retryAfterMilliseconds;

  public RateLimitException(int status, String message) {
    this(status, message, Optional.empty());
  }

  public RateLimitException(int status, String message, Optional<Long> retryAfterMilliseconds) {
    super(status, message);
    this.retryAfterMilliseconds = retryAfterMilliseconds;
  }

  public Optional<Long> getRetryAfterMilliseconds() {
    return retryAfterMilliseconds;
  }
}
