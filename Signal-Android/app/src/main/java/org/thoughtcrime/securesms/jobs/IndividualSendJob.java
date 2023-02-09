package org.thoughtcrime.securesms.jobs;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Stream;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil;
import org.thoughtcrime.securesms.database.MessageTable;
import org.thoughtcrime.securesms.database.MessageTable.SyncMessageId;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.PaymentTable;
import org.thoughtcrime.securesms.database.RecipientTable.UnidentifiedAccessMode;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingMessage;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.service.ExpiringMessageManager;
import org.thoughtcrime.securesms.transport.InsecureFallbackApprovalException;
import org.thoughtcrime.securesms.transport.RetryLaterException;
import org.thoughtcrime.securesms.transport.UndeliverableMessageException;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.SignalServiceMessageSender.IndividualSendEvents;
import org.whispersystems.signalservice.api.crypto.ContentHint;
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccessPair;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServicePreview;
import org.whispersystems.signalservice.api.messages.shared.SharedContact;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.ProofRequiredException;
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException;
import org.whispersystems.signalservice.api.push.exceptions.UnregisteredUserException;
import org.whispersystems.signalservice.api.util.UuidUtil;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.BodyRange;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.DataMessage;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class IndividualSendJob extends PushSendJob {

  public static final String KEY = "PushMediaSendJob";

  private static final String TAG = Log.tag(IndividualSendJob.class);

  private static final String KEY_MESSAGE_ID = "message_id";

  private final long messageId;

  public IndividualSendJob(long messageId, @NonNull Recipient recipient, boolean hasMedia, boolean isScheduledSend) {
    this(new Parameters.Builder()
                       .setQueue(isScheduledSend ? recipient.getId().toScheduledSendQueueKey() : recipient.getId().toQueueKey(hasMedia))
                       .addConstraint(NetworkConstraint.KEY)
                       .setLifespan(TimeUnit.DAYS.toMillis(1))
                       .setMaxAttempts(Parameters.UNLIMITED)
                       .build(),
         messageId);
  }

  private IndividualSendJob(Job.Parameters parameters, long messageId) {
    super(parameters);
    this.messageId = messageId;
  }

  public static Job create(long messageId, @NonNull Recipient recipient, boolean hasMedia, boolean isScheduledSend) {
    if (!recipient.hasServiceId()) {
      throw new AssertionError("No ServiceId!");
    }

    if (recipient.isGroup()) {
      throw new AssertionError("This job does not send group messages!");
    }

    return new IndividualSendJob(messageId, recipient, hasMedia, isScheduledSend);
  }

  @WorkerThread
  public static void enqueue(@NonNull Context context, @NonNull JobManager jobManager, long messageId, @NonNull Recipient recipient, boolean isScheduledSend) {
    try {
      OutgoingMessage message = SignalDatabase.messages().getOutgoingMessage(messageId);
      if (message.getScheduledDate() != -1) {
        ApplicationDependencies.getScheduledMessageManager().scheduleIfNecessary();
        return;
      }
      Set<String> attachmentUploadIds = enqueueCompressingAndUploadAttachmentsChains(jobManager, message);

      jobManager.add(IndividualSendJob.create(messageId, recipient, attachmentUploadIds.size() > 0, isScheduledSend), attachmentUploadIds, isScheduledSend ? null : recipient.getId().toQueueKey());
    } catch (NoSuchMessageException | MmsException e) {
      Log.w(TAG, "Failed to enqueue message.", e);
      SignalDatabase.messages().markAsSentFailed(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
    }
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putLong(KEY_MESSAGE_ID, messageId).build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onAdded() {
    SignalDatabase.messages().markAsSending(messageId);
  }

  @Override
  public void onPushSend()
      throws IOException, MmsException, NoSuchMessageException, UndeliverableMessageException, RetryLaterException
  {
    ExpiringMessageManager expirationManager = ApplicationDependencies.getExpiringMessageManager();
    MessageTable    database = SignalDatabase.messages();
    OutgoingMessage message  = database.getOutgoingMessage(messageId);
    long            threadId = database.getMessageRecord(messageId).getThreadId();

    if (database.isSent(messageId)) {
      warn(TAG, String.valueOf(message.getSentTimeMillis()), "Message " + messageId + " was already sent. Ignoring.");
      return;
    }

    try {
      log(TAG, String.valueOf(message.getSentTimeMillis()), "Sending message: " + messageId + ", Recipient: " + message.getRecipient().getId() + ", Thread: " + threadId + ", Attachments: " + buildAttachmentString(message.getAttachments()));

      RecipientUtil.shareProfileIfFirstSecureMessage(message.getRecipient());

      Recipient              recipient  = message.getRecipient().fresh();
      byte[]                 profileKey = recipient.getProfileKey();
      UnidentifiedAccessMode accessMode = recipient.getUnidentifiedAccessMode();

      boolean unidentified = deliver(message);

      database.markAsSent(messageId, true);
      markAttachmentsUploaded(messageId, message);
      database.markUnidentified(messageId, unidentified);

      if (recipient.isSelf()) {
        SyncMessageId id = new SyncMessageId(recipient.getId(), message.getSentTimeMillis());
        SignalDatabase.messages().incrementDeliveryReceiptCount(id, System.currentTimeMillis());
        SignalDatabase.messages().incrementReadReceiptCount(id, System.currentTimeMillis());
        SignalDatabase.messages().incrementViewedReceiptCount(id, System.currentTimeMillis());
      }

      if (unidentified && accessMode == UnidentifiedAccessMode.UNKNOWN && profileKey == null) {
        log(TAG, String.valueOf(message.getSentTimeMillis()), "Marking recipient as UD-unrestricted following a UD send.");
        SignalDatabase.recipients().setUnidentifiedAccessMode(recipient.getId(), UnidentifiedAccessMode.UNRESTRICTED);
      } else if (unidentified && accessMode == UnidentifiedAccessMode.UNKNOWN) {
        log(TAG, String.valueOf(message.getSentTimeMillis()), "Marking recipient as UD-enabled following a UD send.");
        SignalDatabase.recipients().setUnidentifiedAccessMode(recipient.getId(), UnidentifiedAccessMode.ENABLED);
      } else if (!unidentified && accessMode != UnidentifiedAccessMode.DISABLED) {
        log(TAG, String.valueOf(message.getSentTimeMillis()), "Marking recipient as UD-disabled following a non-UD send.");
        SignalDatabase.recipients().setUnidentifiedAccessMode(recipient.getId(), UnidentifiedAccessMode.DISABLED);
      }

      if (message.getExpiresIn() > 0 && !message.isExpirationUpdate()) {
        database.markExpireStarted(messageId);
        expirationManager.scheduleDeletion(messageId, true, message.getExpiresIn());
      }

      if (message.isViewOnce()) {
        SignalDatabase.attachments().deleteAttachmentFilesForViewOnceMessage(messageId);
      }

      log(TAG, String.valueOf(message.getSentTimeMillis()), "Sent message: " + messageId);

    } catch (InsecureFallbackApprovalException ifae) {
      warn(TAG, "Failure", ifae);
      database.markAsPendingInsecureSmsFallback(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
      ApplicationDependencies.getJobManager().add(new DirectoryRefreshJob(false));
    } catch (UntrustedIdentityException uie) {
      warn(TAG, "Failure", uie);
      RecipientId recipientId = Recipient.external(context, uie.getIdentifier()).getId();
      database.addMismatchedIdentity(messageId, recipientId, uie.getIdentityKey());
      database.markAsSentFailed(messageId);
      RetrieveProfileJob.enqueue(recipientId);
    } catch (ProofRequiredException e) {
      handleProofRequiredException(context, e, SignalDatabase.threads().getRecipientForThreadId(threadId), threadId, messageId, true);
    }
  }

  @Override
  public void onFailure() {
    SignalDatabase.messages().markAsSentFailed(messageId);
    notifyMediaMessageDeliveryFailed(context, messageId);
  }

  private boolean deliver(OutgoingMessage message)
      throws IOException, InsecureFallbackApprovalException, UntrustedIdentityException, UndeliverableMessageException
  {
    if (message.getRecipient() == null) {
      throw new UndeliverableMessageException("No destination address.");
    }

    try {
      rotateSenderCertificateIfNecessary();

      Recipient messageRecipient = message.getRecipient().fresh();

      if (messageRecipient.isUnregistered()) {
        throw new UndeliverableMessageException(messageRecipient.getId() + " not registered!");
      }

      SignalServiceMessageSender                 messageSender       = ApplicationDependencies.getSignalServiceMessageSender();
      SignalServiceAddress                       address             = RecipientUtil.toSignalServiceAddress(context, messageRecipient);
      List<Attachment>                           attachments         = Stream.of(message.getAttachments()).filterNot(Attachment::isSticker).toList();
      List<SignalServiceAttachment>              serviceAttachments  = getAttachmentPointersFor(attachments);
      Optional<byte[]>                           profileKey          = getProfileKey(messageRecipient);
      Optional<SignalServiceDataMessage.Sticker> sticker             = getStickerFor(message);
      List<SharedContact>                        sharedContacts      = getSharedContactsFor(message);
      List<SignalServicePreview>                 previews            = getPreviewsFor(message);
      SignalServiceDataMessage.GiftBadge         giftBadge           = getGiftBadgeFor(message);
      SignalServiceDataMessage.Payment           payment             = getPayment(message);
      List<BodyRange>                            bodyRanges          = getBodyRanges(message);
      SignalServiceDataMessage.Builder           mediaMessageBuilder = SignalServiceDataMessage.newBuilder()
                                                                                               .withBody(message.getBody())
                                                                                               .withAttachments(serviceAttachments)
                                                                                               .withTimestamp(message.getSentTimeMillis())
                                                                                               .withExpiration((int)(message.getExpiresIn() / 1000))
                                                                                               .withViewOnce(message.isViewOnce())
                                                                                               .withProfileKey(profileKey.orElse(null))
                                                                                               .withSticker(sticker.orElse(null))
                                                                                               .withSharedContacts(sharedContacts)
                                                                                               .withPreviews(previews)
                                                                                               .withGiftBadge(giftBadge)
                                                                                               .asExpirationUpdate(message.isExpirationUpdate())
                                                                                               .asEndSessionMessage(message.isEndSession())
                                                                                               .withPayment(payment)
                                                                                               .withBodyRanges(bodyRanges);

      if (message.getParentStoryId() != null) {
        try {
          MessageRecord storyRecord    = SignalDatabase.messages().getMessageRecord(message.getParentStoryId().asMessageId().getId());
          Recipient     storyRecipient = storyRecord.isOutgoing() ? Recipient.self() : storyRecord.getRecipient();

          SignalServiceDataMessage.StoryContext storyContext = new SignalServiceDataMessage.StoryContext(storyRecipient.requireServiceId(), storyRecord.getDateSent());
          mediaMessageBuilder.withStoryContext(storyContext);

          Optional<SignalServiceDataMessage.Reaction> reaction = getStoryReactionFor(message, storyContext);
          if (reaction.isPresent()) {
            mediaMessageBuilder.withReaction(reaction.get());
            mediaMessageBuilder.withBody(null);
          }
        } catch (NoSuchMessageException e) {
          throw new UndeliverableMessageException(e);
        }
      } else {
        mediaMessageBuilder.withQuote(getQuoteFor(message).orElse(null));
      }

      if (message.getGiftBadge() != null || message.isPaymentsNotification()) {
        mediaMessageBuilder.withBody(null);
      }

      SignalServiceDataMessage mediaMessage = mediaMessageBuilder.build();

      if (Util.equals(SignalStore.account().getAci(), address.getServiceId())) {
        Optional<UnidentifiedAccessPair> syncAccess = UnidentifiedAccessUtil.getAccessForSync(context);
        SendMessageResult                result     = messageSender.sendSyncMessage(mediaMessage);
        SignalDatabase.messageLog().insertIfPossible(messageRecipient.getId(), message.getSentTimeMillis(), result, ContentHint.RESENDABLE, new MessageId(messageId), false);
        return syncAccess.isPresent();
      } else {
        SendMessageResult result = messageSender.sendDataMessage(address, UnidentifiedAccessUtil.getAccessFor(context, messageRecipient), ContentHint.RESENDABLE, mediaMessage, IndividualSendEvents.EMPTY, message.isUrgent(), messageRecipient.needsPniSignature());

        SignalDatabase.messageLog().insertIfPossible(messageRecipient.getId(), message.getSentTimeMillis(), result, ContentHint.RESENDABLE, new MessageId(messageId), message.isUrgent());

        if (messageRecipient.needsPniSignature()) {
          SignalDatabase.pendingPniSignatureMessages().insertIfNecessary(messageRecipient.getId(), message.getSentTimeMillis(), result);
        }

        return result.getSuccess().isUnidentified();
      }
    } catch (UnregisteredUserException e) {
      warn(TAG, String.valueOf(message.getSentTimeMillis()), e);
      throw new InsecureFallbackApprovalException(e);
    } catch (FileNotFoundException e) {
      warn(TAG, String.valueOf(message.getSentTimeMillis()), e);
      throw new UndeliverableMessageException(e);
    } catch (ServerRejectedException e) {
      throw new UndeliverableMessageException(e);
    }
  }

  private SignalServiceDataMessage.Payment getPayment(OutgoingMessage message) {
    if (message.isPaymentsNotification()) {
      UUID                            paymentUuid = UuidUtil.parseOrThrow(message.getBody());
      PaymentTable.PaymentTransaction payment     = SignalDatabase.payments().getPayment(paymentUuid);

      if (payment == null) {
        Log.w(TAG, "Could not find payment, cannot send notification " + paymentUuid);
        return null;
      }

      if (payment.getReceipt() == null) {
        Log.w(TAG, "Could not find payment receipt, cannot send notification " + paymentUuid);
        return null;
      }

      return new SignalServiceDataMessage.Payment(new SignalServiceDataMessage.PaymentNotification(payment.getReceipt(), payment.getNote()), null);
    } else {
      DataMessage.Payment.Activation.Type type = null;

      if (message.isRequestToActivatePayments()) {
        type = DataMessage.Payment.Activation.Type.REQUEST;
      } else if (message.isPaymentsActivated()) {
        type = DataMessage.Payment.Activation.Type.ACTIVATED;
      }

      if (type != null) {
        return new SignalServiceDataMessage.Payment(null, new SignalServiceDataMessage.PaymentActivation(type));
      } else {
        return null;
      }
    }
  }

  public static long getMessageId(@NonNull Data data) {
    return data.getLong(KEY_MESSAGE_ID);
  }

  public static final class Factory implements Job.Factory<IndividualSendJob> {
    @Override
    public @NonNull IndividualSendJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new IndividualSendJob(parameters, data.getLong(KEY_MESSAGE_ID));
    }
  }
}
