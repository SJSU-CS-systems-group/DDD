package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.recipients.RecipientId;

/**
 * Runs in the same queue as messages for the group.
 */
final class GroupCallPeekWorkerJob extends BaseJob {

  public static final String KEY = "GroupCallPeekWorkerJob";

  private static final String KEY_GROUP_RECIPIENT_ID        = "group_recipient_id";

  @NonNull private final RecipientId groupRecipientId;

  public GroupCallPeekWorkerJob(@NonNull RecipientId groupRecipientId) {
    this(new Parameters.Builder()
                       .setQueue(PushProcessMessageJob.getQueueName(groupRecipientId))
                       .setMaxInstancesForQueue(2)
                       .build(),
         groupRecipientId);
  }

  private GroupCallPeekWorkerJob(@NonNull Parameters parameters, @NonNull RecipientId groupRecipientId) {
    super(parameters);
    this.groupRecipientId = groupRecipientId;
  }

  @Override
  protected void onRun() {
    ApplicationDependencies.getSignalCallManager().peekGroupCall(groupRecipientId);
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    return false;
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder()
                   .putString(KEY_GROUP_RECIPIENT_ID, groupRecipientId.serialize())
                   .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onFailure() {
  }

  public static final class Factory implements Job.Factory<GroupCallPeekWorkerJob> {

    @Override
    public @NonNull GroupCallPeekWorkerJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new GroupCallPeekWorkerJob(parameters, RecipientId.from(data.getString(KEY_GROUP_RECIPIENT_ID)));
    }
  }
}
