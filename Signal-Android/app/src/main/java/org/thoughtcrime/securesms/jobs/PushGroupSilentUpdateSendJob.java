package org.thoughtcrime.securesms.jobs;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;
import com.google.protobuf.InvalidProtocolBufferException;

import org.signal.core.util.logging.Log;
import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.thoughtcrime.securesms.database.RecipientTable;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.messages.GroupSendUtil;
import org.thoughtcrime.securesms.mms.MessageGroupContext;
import org.thoughtcrime.securesms.mms.OutgoingMessage;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.transport.RetryLaterException;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.whispersystems.signalservice.api.crypto.ContentHint;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.groupsv2.DecryptedGroupUtil;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceGroupV2;
import org.whispersystems.signalservice.api.push.ServiceId;
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException;
import org.whispersystems.signalservice.api.util.UuidUtil;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Sends an update to a group without inserting a change message locally.
 * <p>
 * An example usage would be to update a group with a profile key change.
 */
public final class PushGroupSilentUpdateSendJob extends BaseJob {

  public static final String KEY = "PushGroupSilentSendJob";

  private static final String TAG = Log.tag(PushGroupSilentUpdateSendJob.class);

  private static final String KEY_RECIPIENTS              = "recipients";
  private static final String KEY_INITIAL_RECIPIENT_COUNT = "initial_recipient_count";
  private static final String KEY_TIMESTAMP               = "timestamp";
  private static final String KEY_GROUP_CONTEXT_V2        = "group_context_v2";

  private final List<RecipientId>                  recipients;
  private final int                                initialRecipientCount;
  private final SignalServiceProtos.GroupContextV2 groupContextV2;
  private final long                               timestamp;

  @WorkerThread
  public static @NonNull Job create(@NonNull Context context,
                                    @NonNull GroupId.V2 groupId,
                                    @NonNull DecryptedGroup decryptedGroup,
                                    @NonNull OutgoingMessage groupMessage)
  {
    List<UUID> memberUuids  = DecryptedGroupUtil.toUuidList(decryptedGroup.getMembersList());
    List<UUID> pendingUuids = DecryptedGroupUtil.pendingToUuidList(decryptedGroup.getPendingMembersList());

    Set<RecipientId> recipients = Stream.concat(Stream.of(memberUuids), Stream.of(pendingUuids))
                                        .filter(uuid -> !UuidUtil.UNKNOWN_UUID.equals(uuid))
                                        .filter(uuid -> !SignalStore.account().requireAci().uuid().equals(uuid))
                                        .map(uuid -> Recipient.externalPush(ServiceId.from(uuid)))
                                        .filter(recipient -> recipient.getRegistered() != RecipientTable.RegisteredState.NOT_REGISTERED)
                                        .map(Recipient::getId)
                                        .collect(Collectors.toSet());

    MessageGroupContext.GroupV2Properties properties   = groupMessage.requireGroupV2Properties();
    SignalServiceProtos.GroupContextV2    groupContext = properties.getGroupContext();

    String queue = Recipient.externalGroupExact(groupId).getId().toQueueKey();

    return new PushGroupSilentUpdateSendJob(new ArrayList<>(recipients),
                                            recipients.size(),
                                            groupMessage.getSentTimeMillis(),
                                            groupContext,
                                            new Parameters.Builder()
                                                          .setQueue(queue)
                                                          .setLifespan(TimeUnit.DAYS.toMillis(1))
                                                          .setMaxAttempts(Parameters.UNLIMITED)
                                                          .build());
  }

  private PushGroupSilentUpdateSendJob(@NonNull List<RecipientId> recipients,
                                       int initialRecipientCount,
                                       long timestamp,
                                       @NonNull SignalServiceProtos.GroupContextV2 groupContextV2,
                                       @NonNull Parameters parameters)
  {
    super(parameters);

    this.recipients            = recipients;
    this.initialRecipientCount = initialRecipientCount;
    this.groupContextV2        = groupContextV2;
    this.timestamp             = timestamp;
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putString(KEY_RECIPIENTS, RecipientId.toSerializedList(recipients))
                             .putInt(KEY_INITIAL_RECIPIENT_COUNT, initialRecipientCount)
                             .putLong(KEY_TIMESTAMP, timestamp)
                             .putString(KEY_GROUP_CONTEXT_V2, Base64.encodeBytes(groupContextV2.toByteArray()))
                             .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  protected void onRun() throws Exception {
    if (!Recipient.self().isRegistered()) {
      throw new NotPushRegisteredException();
    }

    GroupId.V2 groupId = GroupId.v2(GroupUtil.requireMasterKey(groupContextV2.getMasterKey().toByteArray()));

    if (Recipient.externalGroupExact(groupId).isBlocked()) {
      Log.i(TAG, "Not updating group state for blocked group " + groupId);
      return;
    }

    List<Recipient> destinations = Stream.of(recipients).map(Recipient::resolved).toList();
    List<Recipient> completions  = deliver(destinations, groupId);

    for (Recipient completion : completions) {
      recipients.remove(completion.getId());
    }

    Log.i(TAG, "Completed now: " + completions.size() + ", Remaining: " + recipients.size());

    if (!recipients.isEmpty()) {
      Log.w(TAG, "Still need to send to " + recipients.size() + " recipients. Retrying.");
      throw new RetryLaterException();
    }
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    if (e instanceof ServerRejectedException) return false;
    if (e instanceof NotPushRegisteredException) return false;
    return e instanceof IOException ||
           e instanceof RetryLaterException;
  }

  @Override
  public void onFailure() {
    Log.w(TAG, "Failed to send remote delete to all recipients! (" + (initialRecipientCount - recipients.size() + "/" + initialRecipientCount + ")") );
  }

  private @NonNull List<Recipient> deliver(@NonNull List<Recipient> destinations, @NonNull GroupId.V2 groupId)
      throws IOException, UntrustedIdentityException
  {
    SignalServiceGroupV2     group            = SignalServiceGroupV2.fromProtobuf(groupContextV2);
    SignalServiceDataMessage groupDataMessage = SignalServiceDataMessage.newBuilder()
                                                                        .withTimestamp(timestamp)
                                                                        .asGroupMessage(group)
                                                                        .build();

    List<SendMessageResult> results = GroupSendUtil.sendUnresendableDataMessage(context, groupId, destinations, false, ContentHint.IMPLICIT, groupDataMessage, false);

    return GroupSendJobHelper.getCompletedSends(destinations, results).completed;
  }

  public static class Factory implements Job.Factory<PushGroupSilentUpdateSendJob> {
    @Override
    public @NonNull PushGroupSilentUpdateSendJob create(@NonNull Parameters parameters, @NonNull Data data) {
      List<RecipientId> recipients            = RecipientId.fromSerializedList(data.getString(KEY_RECIPIENTS));
      int               initialRecipientCount = data.getInt(KEY_INITIAL_RECIPIENT_COUNT);
      long              timestamp             = data.getLong(KEY_TIMESTAMP);
      byte[]            contextBytes          = Base64.decodeOrThrow(data.getString(KEY_GROUP_CONTEXT_V2));

      SignalServiceProtos.GroupContextV2 groupContextV2;
      try {
        groupContextV2 = SignalServiceProtos.GroupContextV2.parseFrom(contextBytes);
      } catch (InvalidProtocolBufferException e) {
        throw new AssertionError(e);
      }

      return new PushGroupSilentUpdateSendJob(recipients, initialRecipientCount, timestamp, groupContextV2, parameters);
    }
  }
}
