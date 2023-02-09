package org.thoughtcrime.securesms.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SessionStore;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.crypto.ReentrantSessionLock;
import org.thoughtcrime.securesms.crypto.storage.SignalIdentityKeyStore;
import org.thoughtcrime.securesms.database.GroupTable;
import org.thoughtcrime.securesms.database.IdentityTable;
import org.thoughtcrime.securesms.database.MessageTable;
import org.thoughtcrime.securesms.database.MessageTable.InsertResult;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.GroupRecord;
import org.thoughtcrime.securesms.database.model.IdentityRecord;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingMessage;
import org.thoughtcrime.securesms.notifications.v2.ConversationId;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.sms.IncomingIdentityDefaultMessage;
import org.thoughtcrime.securesms.sms.IncomingIdentityUpdateMessage;
import org.thoughtcrime.securesms.sms.IncomingIdentityVerifiedMessage;
import org.thoughtcrime.securesms.sms.IncomingTextMessage;
import org.thoughtcrime.securesms.util.concurrent.ListenableFuture;
import org.thoughtcrime.securesms.util.concurrent.SettableFuture;
import org.signal.core.util.concurrent.SimpleTask;
import org.whispersystems.signalservice.api.SignalSessionLock;
import org.whispersystems.signalservice.api.messages.multidevice.VerifiedMessage;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;

import java.util.List;
import java.util.Optional;

public final class IdentityUtil {

  private IdentityUtil() {}

  private static final String TAG = Log.tag(IdentityUtil.class);

  public static ListenableFuture<Optional<IdentityRecord>> getRemoteIdentityKey(final Context context, final Recipient recipient) {
    final SettableFuture<Optional<IdentityRecord>> future      = new SettableFuture<>();
    final RecipientId                              recipientId = recipient.getId();

    SimpleTask.run(SignalExecutors.BOUNDED,
                   () -> ApplicationDependencies.getProtocolStore().aci().identities().getIdentityRecord(recipientId),
                   future::set);

    return future;
  }

  public static void markIdentityVerified(Context context, Recipient recipient, boolean verified, boolean remote)
  {
    long         time          = System.currentTimeMillis();
    MessageTable smsDatabase   = SignalDatabase.messages();
    GroupTable   groupDatabase = SignalDatabase.groups();

    try (GroupTable.Reader reader = groupDatabase.getGroups()) {

      GroupRecord groupRecord;

      while ((groupRecord = reader.getNext()) != null) {
        if (groupRecord.getMembers().contains(recipient.getId()) && groupRecord.isActive() && !groupRecord.isMms()) {

          if (remote) {
            IncomingTextMessage incoming = new IncomingTextMessage(recipient.getId(), 1, time, -1, time, null, Optional.of(groupRecord.getId()), 0, false, null);

            if (verified) incoming = new IncomingIdentityVerifiedMessage(incoming);
            else          incoming = new IncomingIdentityDefaultMessage(incoming);

            smsDatabase.insertMessageInbox(incoming);
          } else {
            RecipientId recipientId    = SignalDatabase.recipients().getOrInsertFromGroupId(groupRecord.getId());
            Recipient   groupRecipient = Recipient.resolved(recipientId);
            long        threadId       = SignalDatabase.threads().getOrCreateThreadIdFor(groupRecipient);

            OutgoingMessage outgoing;
            if (verified) {
              outgoing = OutgoingMessage.identityVerifiedMessage(recipient, time);
            } else {
              outgoing = OutgoingMessage.identityDefaultMessage(recipient, time);
            }

            try {
              SignalDatabase.messages().insertMessageOutbox(outgoing, threadId, false, null);
            } catch (MmsException e) {
              throw new AssertionError(e);
            }
            SignalDatabase.threads().update(threadId, true);
          }
        }
      }
    }

    if (remote) {
      IncomingTextMessage incoming = new IncomingTextMessage(recipient.getId(), 1, time, -1, time, null, Optional.empty(), 0, false, null);

      if (verified) incoming = new IncomingIdentityVerifiedMessage(incoming);
      else          incoming = new IncomingIdentityDefaultMessage(incoming);

      smsDatabase.insertMessageInbox(incoming);
    } else {
      OutgoingMessage outgoing;
      if (verified) {
        outgoing = OutgoingMessage.identityVerifiedMessage(recipient, time);
      } else {
        outgoing = OutgoingMessage.identityDefaultMessage(recipient, time);
      }

      long threadId = SignalDatabase.threads().getOrCreateThreadIdFor(recipient);

      Log.i(TAG, "Inserting verified outbox...");
      try {
        SignalDatabase.messages().insertMessageOutbox(outgoing, threadId, false, null);
      } catch (MmsException e) {
        throw new AssertionError();
      }
      boolean keepThreadArchived = SignalStore.settings().shouldKeepMutedChatsArchived() && recipient.isMuted();
      SignalDatabase.threads().update(threadId, !keepThreadArchived);
    }
  }

  public static void markIdentityUpdate(@NonNull Context context, @NonNull RecipientId recipientId) {
    Log.w(TAG, "Inserting safety number change event(s) for " + recipientId, new Throwable());

    long         time          = System.currentTimeMillis();
    MessageTable smsDatabase   = SignalDatabase.messages();
    GroupTable   groupDatabase = SignalDatabase.groups();

    try (GroupTable.Reader reader = groupDatabase.getGroups()) {
      GroupRecord groupRecord;

      while ((groupRecord = reader.getNext()) != null) {
        if (groupRecord.getMembers().contains(recipientId) && groupRecord.isActive()) {
          IncomingTextMessage           incoming    = new IncomingTextMessage(recipientId, 1, time, time, time, null, Optional.of(groupRecord.getId()), 0, false, null);
          IncomingIdentityUpdateMessage groupUpdate = new IncomingIdentityUpdateMessage(incoming);

          smsDatabase.insertMessageInbox(groupUpdate);
        }
      }
    }

    IncomingTextMessage           incoming         = new IncomingTextMessage(recipientId, 1, time, -1, time, null, Optional.empty(), 0, false, null);
    IncomingIdentityUpdateMessage individualUpdate = new IncomingIdentityUpdateMessage(incoming);
    Optional<InsertResult>        insertResult     = smsDatabase.insertMessageInbox(individualUpdate);

    if (insertResult.isPresent()) {
      ApplicationDependencies.getMessageNotifier().updateNotification(context, ConversationId.forConversation(insertResult.get().getThreadId()));
    }
  }

  public static void saveIdentity(String user, IdentityKey identityKey) {
    try(SignalSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      SessionStore          sessionStore     = ApplicationDependencies.getProtocolStore().aci();
      SignalProtocolAddress address          = new SignalProtocolAddress(user, SignalServiceAddress.DEFAULT_DEVICE_ID);

      if (ApplicationDependencies.getProtocolStore().aci().identities().saveIdentity(address, identityKey)) {
        if (sessionStore.containsSession(address)) {
          SessionRecord sessionRecord = sessionStore.loadSession(address);
          sessionRecord.archiveCurrentState();

          sessionStore.storeSession(address, sessionRecord);
        }
      }
    }
  }

  public static void processVerifiedMessage(Context context, VerifiedMessage verifiedMessage) {
    try(SignalSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      SignalIdentityKeyStore   identityStore  = ApplicationDependencies.getProtocolStore().aci().identities();
      Recipient                recipient      = Recipient.externalPush(verifiedMessage.getDestination());

      if (recipient.isSelf()) {
        Log.w(TAG, "Attempting to change verified status of self to " + verifiedMessage.getVerified() + ", skipping.");
        return;
      }

      Optional<IdentityRecord> identityRecord = identityStore.getIdentityRecord(recipient.getId());

      if (!identityRecord.isPresent() && verifiedMessage.getVerified() == VerifiedMessage.VerifiedState.DEFAULT) {
        Log.w(TAG, "No existing record for default status");
        return;
      }

      if (verifiedMessage.getVerified() == VerifiedMessage.VerifiedState.DEFAULT              &&
          identityRecord.isPresent()                                                          &&
          identityRecord.get().getIdentityKey().equals(verifiedMessage.getIdentityKey())      &&
          identityRecord.get().getVerifiedStatus() != IdentityTable.VerifiedStatus.DEFAULT)
      {
        Log.i(TAG, "Setting " + recipient.getId() + " verified status to " + IdentityTable.VerifiedStatus.DEFAULT);
        identityStore.setVerified(recipient.getId(), identityRecord.get().getIdentityKey(), IdentityTable.VerifiedStatus.DEFAULT);
        markIdentityVerified(context, recipient, false, true);
      }

      if (verifiedMessage.getVerified() == VerifiedMessage.VerifiedState.VERIFIED &&
          (!identityRecord.isPresent() ||
              (identityRecord.isPresent() && !identityRecord.get().getIdentityKey().equals(verifiedMessage.getIdentityKey())) ||
              (identityRecord.isPresent() && identityRecord.get().getVerifiedStatus() != IdentityTable.VerifiedStatus.VERIFIED)))
      {
        Log.i(TAG, "Setting " + recipient.getId() + " verified status to " + IdentityTable.VerifiedStatus.VERIFIED);
        saveIdentity(verifiedMessage.getDestination().getIdentifier(), verifiedMessage.getIdentityKey());
        identityStore.setVerified(recipient.getId(), verifiedMessage.getIdentityKey(), IdentityTable.VerifiedStatus.VERIFIED);
        markIdentityVerified(context, recipient, true, true);
      }
    }
  }


  public static @Nullable String getUnverifiedBannerDescription(@NonNull Context context,
                                                                @NonNull List<Recipient> unverified)
  {
    return getPluralizedIdentityDescription(context, unverified,
                                            R.string.IdentityUtil_unverified_banner_one,
                                            R.string.IdentityUtil_unverified_banner_two,
                                            R.string.IdentityUtil_unverified_banner_many);
  }

  public static @Nullable String getUnverifiedSendDialogDescription(@NonNull Context context,
                                                                    @NonNull List<Recipient> unverified)
  {
    return getPluralizedIdentityDescription(context, unverified,
                                            R.string.IdentityUtil_unverified_dialog_one,
                                            R.string.IdentityUtil_unverified_dialog_two,
                                            R.string.IdentityUtil_unverified_dialog_many);
  }

  public static @Nullable String getUntrustedSendDialogDescription(@NonNull Context context,
                                                                   @NonNull List<Recipient> untrusted)
  {
    return getPluralizedIdentityDescription(context, untrusted,
                                            R.string.IdentityUtil_untrusted_dialog_one,
                                            R.string.IdentityUtil_untrusted_dialog_two,
                                            R.string.IdentityUtil_untrusted_dialog_many);
  }

  private static @Nullable String getPluralizedIdentityDescription(@NonNull Context context,
                                                                   @NonNull List<Recipient> recipients,
                                                                   @StringRes int resourceOne,
                                                                   @StringRes int resourceTwo,
                                                                   @StringRes int resourceMany)
  {
    if (recipients.isEmpty()) return null;

    if (recipients.size() == 1) {
      String name = recipients.get(0).getDisplayName(context);
      return context.getString(resourceOne, name);
    } else {
      String firstName  = recipients.get(0).getDisplayName(context);
      String secondName = recipients.get(1).getDisplayName(context);

      if (recipients.size() == 2) {
        return context.getString(resourceTwo, firstName, secondName);
      } else {
        int    othersCount = recipients.size() - 2;
        String nMore       = context.getResources().getQuantityString(R.plurals.identity_others, othersCount, othersCount);

        return context.getString(resourceMany, firstName, secondName, nMore);
      }
    }
  }
}
