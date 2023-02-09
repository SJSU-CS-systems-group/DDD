package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;

import org.greenrobot.eventbus.EventBus;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.ratelimit.RateLimitUtil;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;

import java.util.concurrent.TimeUnit;

/**
 * Send a push challenge token to the service as a way of proving that your device has FCM.
 */
public final class SubmitRateLimitPushChallengeJob extends BaseJob {

  public static final String KEY = "SubmitRateLimitPushChallengeJob";

  private static final String KEY_CHALLENGE = "challenge";

  private final String challenge;

  public SubmitRateLimitPushChallengeJob(@NonNull String challenge) {
    this(new Parameters.Builder()
                       .addConstraint(NetworkConstraint.KEY)
                       .setLifespan(TimeUnit.HOURS.toMillis(1))
                       .setMaxAttempts(Parameters.UNLIMITED)
                       .build(),
         challenge);
  }

  private SubmitRateLimitPushChallengeJob(@NonNull Parameters parameters, @NonNull String challenge) {
    super(parameters);
    this.challenge = challenge;
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putString(KEY_CHALLENGE, challenge).build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  protected void onRun() throws Exception {
    ApplicationDependencies.getSignalServiceAccountManager().submitRateLimitPushChallenge(challenge);
    SignalStore.rateLimit().onProofAccepted();
    EventBus.getDefault().post(new SuccessEvent());
    RateLimitUtil.retryAllRateLimitedMessages(context);
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    return e instanceof PushNetworkException;
  }

  @Override
  public void onFailure() {
  }

  public static final class SuccessEvent {
  }

  public static class Factory implements Job.Factory<SubmitRateLimitPushChallengeJob> {
    @Override
    public @NonNull SubmitRateLimitPushChallengeJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new SubmitRateLimitPushChallengeJob(parameters, data.getString(KEY_CHALLENGE));
    }
  }
}
