package org.thoughtcrime.securesms.stickers;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.signal.libsignal.protocol.util.Pair;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.keyboard.sticker.KeyboardStickerListAdapter;
import org.thoughtcrime.securesms.mms.GlideRequests;
import org.thoughtcrime.securesms.util.ViewUtil;

import java.lang.ref.WeakReference;

public class StickerRolloverTouchListener implements RecyclerView.OnItemTouchListener {
  private final StickerPreviewPopup      popup;
  private final RolloverEventListener    eventListener;
  private final RolloverStickerRetriever stickerRetriever;

  private WeakReference<View> currentView;
  private boolean             hoverMode;

  public StickerRolloverTouchListener(@NonNull Context context,
                                      @NonNull GlideRequests glideRequests,
                                      @NonNull RolloverEventListener eventListener,
                                      @NonNull RolloverStickerRetriever stickerRetriever)
  {
    this.eventListener    = eventListener;
    this.stickerRetriever = stickerRetriever;
    this.popup            = new StickerPreviewPopup(context, glideRequests);
    this.currentView      = new WeakReference<>(null);

    popup.setAnimationStyle(R.style.StickerPopupAnimation);
  }

  @Override
  public boolean onInterceptTouchEvent(@NonNull RecyclerView recyclerView, @NonNull MotionEvent motionEvent) {
    return hoverMode;
  }

  @Override
  public void onTouchEvent(@NonNull RecyclerView recyclerView, @NonNull MotionEvent motionEvent) {
    switch (motionEvent.getAction()) {
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        hoverMode = false;
        popup.dismiss();
        eventListener.onStickerPopupEnded();
        currentView.clear();
        break;
      default:
        for (int i = 0, len = recyclerView.getChildCount(); i < len; i++) {
          View child = recyclerView.getChildAt(i);

          if (ViewUtil.isPointInsideView(recyclerView, motionEvent.getRawX(), motionEvent.getRawY()) &&
              ViewUtil.isPointInsideView(child, motionEvent.getRawX(), motionEvent.getRawY()) &&
              child != currentView.get())
          {
            showStickerForView(recyclerView, child);
            currentView = new WeakReference<>(child);
            break;
          }
        }
    }
  }

  @Override
  public void onRequestDisallowInterceptTouchEvent(boolean b) {
  }

  public void enterHoverMode(@NonNull RecyclerView recyclerView, View targetView) {
    this.hoverMode = true;
    showStickerForView(recyclerView, targetView);
  }

  public void enterHoverMode(@NonNull RecyclerView recyclerView, @NonNull KeyboardStickerListAdapter.Sticker sticker) {
    this.hoverMode = true;
    showSticker(recyclerView, sticker.getUri(), sticker.getStickerRecord().getEmoji());
  }

  private void showStickerForView(@NonNull RecyclerView recyclerView, @NonNull View view) {
    Pair<Object, String> stickerData = stickerRetriever.getStickerDataFromView(view);

    if (stickerData != null) {
      showSticker(recyclerView, stickerData.first(), stickerData.second());
    }
  }

  private void showSticker(@NonNull RecyclerView recyclerView, @NonNull Object toLoad, @NonNull String emoji) {
    if (!popup.isShowing()) {
      popup.showAtLocation(recyclerView, Gravity.NO_GRAVITY, 0, 0);
      eventListener.onStickerPopupStarted();
    }
    popup.presentSticker(toLoad, emoji);
  }

  public interface RolloverEventListener {
    void onStickerPopupStarted();

    void onStickerPopupEnded();
  }

  public interface RolloverStickerRetriever {
    @Nullable Pair<Object, String> getStickerDataFromView(@NonNull View view);
  }
}
