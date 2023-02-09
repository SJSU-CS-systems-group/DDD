package org.thoughtcrime.securesms.messagedetails;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.conversation.ConversationMessage;
import org.thoughtcrime.securesms.conversation.colors.Colorizer;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.mms.GlideRequests;

final class MessageDetailsAdapter extends ListAdapter<MessageDetailsAdapter.MessageDetailsViewState<?>, RecyclerView.ViewHolder> {

  private final LifecycleOwner lifecycleOwner;
  private final GlideRequests  glideRequests;
  private final Colorizer      colorizer;
  private final Callbacks      callbacks;

  MessageDetailsAdapter(@NonNull LifecycleOwner lifecycleOwner, @NonNull GlideRequests glideRequests, @NonNull Colorizer colorizer, @NonNull Callbacks callbacks) {
    super(new MessageDetailsDiffer());
    this.lifecycleOwner = lifecycleOwner;
    this.glideRequests  = glideRequests;
    this.colorizer      = colorizer;
    this.callbacks      = callbacks;
  }

  @Override
  public @NonNull RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    switch (viewType) {
      case MessageDetailsViewState.MESSAGE_HEADER:
        return new MessageHeaderViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.message_details_header, parent, false), glideRequests, colorizer);
      case MessageDetailsViewState.RECIPIENT_HEADER:
        return new RecipientHeaderViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.message_details_recipient_header, parent, false));
      case MessageDetailsViewState.RECIPIENT:
        return new RecipientViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.message_details_recipient, parent, false), callbacks);
      default:
        throw new AssertionError("unknown view type");
    }
  }

  @Override
  public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
    if (holder instanceof MessageHeaderViewHolder) {
      ((MessageHeaderViewHolder) holder).bind(lifecycleOwner, (ConversationMessage) getItem(position).data);
    } else if (holder instanceof RecipientHeaderViewHolder) {
      ((RecipientHeaderViewHolder) holder).bind((RecipientHeader) getItem(position).data);
    } else if (holder instanceof RecipientViewHolder) {
      ((RecipientViewHolder) holder).bind((RecipientDeliveryStatus) getItem(position).data);
    } else {
      throw new AssertionError("unknown view holder");
    }
  }

  @Override
  public int getItemViewType(int position) {
    return getItem(position).itemType;
  }

  private static class MessageDetailsDiffer extends DiffUtil.ItemCallback<MessageDetailsViewState<?>> {
    @Override
    public boolean areItemsTheSame(@NonNull MessageDetailsViewState<?> oldItem, @NonNull MessageDetailsViewState<?> newItem) {
      Object oldData = oldItem.data;
      Object newData = newItem.data;

      if (oldData.getClass() == newData.getClass() && oldItem.itemType == newItem.itemType) {
        switch (oldItem.itemType) {
          case MessageDetailsViewState.MESSAGE_HEADER:
            return true;
          case MessageDetailsViewState.RECIPIENT_HEADER:
            return oldData == newData;
          case MessageDetailsViewState.RECIPIENT:
            return ((RecipientDeliveryStatus) oldData).getRecipient().getId().equals(((RecipientDeliveryStatus) newData).getRecipient().getId());
        }
      }

      return false;
    }

    @SuppressLint("DiffUtilEquals")
    @Override
    public boolean areContentsTheSame(@NonNull MessageDetailsViewState<?> oldItem, @NonNull MessageDetailsViewState<?> newItem) {
      Object oldData = oldItem.data;
      Object newData = newItem.data;

      if (oldData.getClass() == newData.getClass() && oldItem.itemType == newItem.itemType) {
        switch (oldItem.itemType) {
          case MessageDetailsViewState.MESSAGE_HEADER:
            return false;
          case MessageDetailsViewState.RECIPIENT_HEADER:
            return true;
          case MessageDetailsViewState.RECIPIENT:
            return ((RecipientDeliveryStatus) oldData).getDeliveryStatus() == ((RecipientDeliveryStatus) newData).getDeliveryStatus();
        }
      }

      return false;
    }
  }

  static final class MessageDetailsViewState<T> {
    public static final int MESSAGE_HEADER   = 0;
    public static final int RECIPIENT_HEADER = 1;
    public static final int RECIPIENT        = 2;

    private final T   data;
    private       int itemType;

    MessageDetailsViewState(T t, int itemType) {
      this.data     = t;
      this.itemType = itemType;
    }
  }

  interface Callbacks {
    void onErrorClicked(@NonNull MessageRecord messageRecord);
  }
}
