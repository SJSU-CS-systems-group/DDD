package org.thoughtcrime.securesms.stickers;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Local model that represents the data present in the libsignal model
 * {@link org.whispersystems.signalservice.api.messages.SignalServiceStickerManifest}.
 */
public final class StickerManifest {

  private final String           packId;
  private final String           packKey;
  private final Optional<String> title;
  private final Optional<String> author;
  private final Optional<Sticker> cover;
  private final List<Sticker>     stickers;

  public StickerManifest(@NonNull String packId,
                         @NonNull String packKey,
                         @NonNull Optional<String> title,
                         @NonNull Optional<String> author,
                         @NonNull Optional<Sticker> cover,
                         @NonNull List<Sticker> stickers)
  {
    this.packId   = packId;
    this.packKey  = packKey;
    this.title    = title;
    this.author   = author;
    this.cover    = cover;
    this.stickers = new ArrayList<>(stickers);
  }

  public @NonNull String getPackId() {
    return packId;
  }

  public @NonNull String getPackKey() {
    return packKey;
  }

  public @NonNull Optional<String> getTitle() {
    return title;
  }

  public @NonNull Optional<String> getAuthor() {
    return author;
  }

  public @NonNull Optional<Sticker> getCover() {
    return cover;
  }

  public @NonNull List<Sticker> getStickers() {
    return stickers;
  }

  public static class Sticker {
    private final String        packId;
    private final String        packKey;
    private final int           id;
    private final String        emoji;
    private final String        contentType;
    private final Optional<Uri> uri;

    public Sticker(@NonNull String packId, @NonNull String packKey, int id, @NonNull String emoji, @Nullable String contentType) {
      this(packId, packKey, id, emoji, contentType, null);
    }

    public Sticker(@NonNull String packId, @NonNull String packKey, int id, @NonNull String emoji, @Nullable String contentType, @Nullable Uri uri) {
      this.packId      = packId;
      this.packKey     = packKey;
      this.id          = id;
      this.emoji       = emoji;
      this.contentType = contentType;
      this.uri         = Optional.ofNullable(uri);
    }

    public @NonNull String getPackId() {
      return packId;
    }

    public @NonNull String getPackKey() {
      return packKey;
    }

    public int getId() {
      return id;
    }

    public String getEmoji() {
      return emoji;
    }

    public @Nullable String getContentType() {
      return contentType;
    }

    public Optional<Uri> getUri() {
      return uri;
    }
  }
}
