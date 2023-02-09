package org.whispersystems.signalservice.api.storage;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.signal.libsignal.protocol.logging.Log;
import org.whispersystems.signalservice.api.push.ServiceId;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.util.ProtoUtil;
import org.whispersystems.signalservice.internal.storage.protos.StoryDistributionListRecord;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class SignalStoryDistributionListRecord implements SignalRecord {

  private static final String TAG = SignalStoryDistributionListRecord.class.getSimpleName();

  private final StorageId                   id;
  private final StoryDistributionListRecord proto;
  private final boolean                     hasUnknownFields;
  private final List<SignalServiceAddress>  recipients;

  public SignalStoryDistributionListRecord(StorageId id, StoryDistributionListRecord proto) {
    this.id               = id;
    this.proto            = proto;
    this.hasUnknownFields = ProtoUtil.hasUnknownFields(proto);
    this.recipients       = proto.getRecipientUuidsList()
                                 .stream()
                                 .map(ServiceId::parseOrNull)
                                 .filter(Objects::nonNull)
                                 .map(SignalServiceAddress::new)
                                 .collect(Collectors.toList());
  }

  @Override
  public StorageId getId() {
    return id;
  }

  @Override
  public SignalStorageRecord asStorageRecord() {
    return SignalStorageRecord.forStoryDistributionList(this);
  }

  public StoryDistributionListRecord toProto() {
    return proto;
  }

  public byte[] serializeUnknownFields() {
    return hasUnknownFields ? proto.toByteArray() : null;
  }

  public byte[] getIdentifier() {
    return proto.getIdentifier().toByteArray();
  }

  public String getName() {
    return proto.getName();
  }

  public List<SignalServiceAddress> getRecipients() {
    return recipients;
  }

  public long getDeletedAtTimestamp() {
    return proto.getDeletedAtTimestamp();
  }

  public boolean allowsReplies() {
    return proto.getAllowsReplies();
  }

  public boolean isBlockList() {
    return proto.getIsBlockList();
  }

  @Override
  public String describeDiff(SignalRecord other) {
    if (other instanceof SignalStoryDistributionListRecord) {
      SignalStoryDistributionListRecord that = (SignalStoryDistributionListRecord) other;
      List<String>                      diff = new LinkedList<>();

      if (!Arrays.equals(this.id.getRaw(), that.id.getRaw())) {
        diff.add("ID");
      }

      if (!Arrays.equals(this.getIdentifier(), that.getIdentifier())) {
        diff.add("Identifier");
      }

      if (!Objects.equals(this.getName(), that.getName())) {
        diff.add("Name");
      }

      if (!Objects.equals(this.recipients, that.recipients)) {
        diff.add("RecipientUuids");
      }

      if (this.getDeletedAtTimestamp() != that.getDeletedAtTimestamp()) {
        diff.add("DeletedAtTimestamp");
      }

      if (this.allowsReplies() != that.allowsReplies()) {
        diff.add("AllowsReplies");
      }

      if (this.isBlockList() != that.isBlockList()) {
        diff.add("BlockList");
      }

      return diff.toString();
    } else {
      return "Different class. " + getClass().getSimpleName() + " | " + other.getClass().getSimpleName();
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SignalStoryDistributionListRecord that = (SignalStoryDistributionListRecord) o;
    return id.equals(that.id) &&
           proto.equals(that.proto);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, proto);
  }

  public static final class Builder {
    private final StorageId                           id;
    private final StoryDistributionListRecord.Builder builder;

    public Builder(byte[] rawId, byte[] serializedUnknowns) {
      this.id = StorageId.forStoryDistributionList(rawId);

      if (serializedUnknowns != null) {
        this.builder = parseUnknowns(serializedUnknowns);
      } else {
        this.builder = StoryDistributionListRecord.newBuilder();
      }
    }

    public Builder setIdentifier(byte[] identifier) {
      builder.setIdentifier(ByteString.copyFrom(identifier));
      return this;
    }

    public Builder setName(String name) {
      builder.setName(name);
      return this;
    }

    public Builder setRecipients(List<SignalServiceAddress> recipients) {
      builder.clearRecipientUuids();
      builder.addAllRecipientUuids(recipients.stream()
                                             .map(SignalServiceAddress::getIdentifier)
                                             .collect(Collectors.toList()));
      return this;
    }

    public Builder setDeletedAtTimestamp(long deletedAtTimestamp) {
      builder.setDeletedAtTimestamp(deletedAtTimestamp);
      return this;
    }

    public Builder setAllowsReplies(boolean allowsReplies) {
      builder.setAllowsReplies(allowsReplies);
      return this;
    }

    public Builder setIsBlockList(boolean isBlockList) {
      builder.setIsBlockList(isBlockList);
      return this;
    }

    public SignalStoryDistributionListRecord build() {
      return new SignalStoryDistributionListRecord(id, builder.build());
    }

    private static StoryDistributionListRecord.Builder parseUnknowns(byte[] serializedUnknowns) {
      try {
        return StoryDistributionListRecord.parseFrom(serializedUnknowns).toBuilder();
      } catch (InvalidProtocolBufferException e) {
        Log.w(TAG, "Failed to combine unknown fields!", e);
        return StoryDistributionListRecord.newBuilder();
      }
    }
  }
}
