package org.thoughtcrime.securesms.conversation;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.thoughtcrime.securesms.R;

import java.util.ArrayList;
import java.util.List;

class AttachmentKeyboardButtonAdapter extends RecyclerView.Adapter<AttachmentKeyboardButtonAdapter.ButtonViewHolder> {

  private final List<AttachmentKeyboardButton> buttons;
  private final Listener                       listener;

  private boolean wallpaperEnabled;

  AttachmentKeyboardButtonAdapter(@NonNull Listener listener) {
    this.buttons  = new ArrayList<>();
    this.listener = listener;

    setHasStableIds(true);
  }

  @Override
  public long getItemId(int position) {
    return buttons.get(position).getTitleRes();
  }

  @Override
  public @NonNull
  ButtonViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    return new ButtonViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.attachment_keyboard_button_item, parent, false));
  }

  @Override
  public void onBindViewHolder(@NonNull ButtonViewHolder holder, int position) {
    holder.bind(buttons.get(position), wallpaperEnabled, listener);
  }

  @Override
  public void onViewRecycled(@NonNull ButtonViewHolder holder) {
    holder.recycle();
  }

  @Override
  public int getItemCount() {
    return buttons.size();
  }

  public void setButtons(@NonNull List<AttachmentKeyboardButton> buttons) {
    this.buttons.clear();
    this.buttons.addAll(buttons);
    notifyDataSetChanged();
  }

  public void setWallpaperEnabled(boolean enabled) {
    if (wallpaperEnabled != enabled) {
      wallpaperEnabled = enabled;
      notifyDataSetChanged();
    }
  }

  interface Listener {
    void onClick(@NonNull AttachmentKeyboardButton button);
  }

  static class ButtonViewHolder extends RecyclerView.ViewHolder {

    private final ImageView image;
    private final TextView  title;

    public ButtonViewHolder(@NonNull View itemView) {
      super(itemView);

      this.image = itemView.findViewById(R.id.icon);
      this.title = itemView.findViewById(R.id.label);
    }

    void bind(@NonNull AttachmentKeyboardButton button, boolean wallpaperEnabled, @NonNull Listener listener) {
      image.setImageResource(button.getIconRes());
      title.setText(button.getTitleRes());

      itemView.setOnClickListener(v -> listener.onClick(button));
    }

    void recycle() {
      itemView.setOnClickListener(null);
    }
  }
}
