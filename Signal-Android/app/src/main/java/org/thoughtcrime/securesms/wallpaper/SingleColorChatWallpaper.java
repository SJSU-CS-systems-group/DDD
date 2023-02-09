package org.thoughtcrime.securesms.wallpaper;

import android.graphics.drawable.ColorDrawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.widget.ImageView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.database.model.databaseprotos.Wallpaper;

import java.util.Objects;

public final class SingleColorChatWallpaper implements ChatWallpaper, Parcelable {

  public static final ChatWallpaper BLUSH      = new SingleColorChatWallpaper(0xFFE26983, 0f);
  public static final ChatWallpaper COPPER     = new SingleColorChatWallpaper(0xFFDF9171, 0f);
  public static final ChatWallpaper DUST       = new SingleColorChatWallpaper(0xFF9E9887, 0f);
  public static final ChatWallpaper CELADON    = new SingleColorChatWallpaper(0xFF89AE8F, 0f);
  public static final ChatWallpaper RAINFOREST = new SingleColorChatWallpaper(0xFF146148, 0f);
  public static final ChatWallpaper PACIFIC    = new SingleColorChatWallpaper(0xFF32C7E2, 0f);
  public static final ChatWallpaper FROST      = new SingleColorChatWallpaper(0xFF7C99B6, 0f);
  public static final ChatWallpaper NAVY       = new SingleColorChatWallpaper(0xFF403B91, 0f);
  public static final ChatWallpaper LILAC      = new SingleColorChatWallpaper(0xFFC988E7, 0f);
  public static final ChatWallpaper PINK       = new SingleColorChatWallpaper(0xFFE297C3, 0f);
  public static final ChatWallpaper EGGPLANT   = new SingleColorChatWallpaper(0xFF624249, 0f);
  public static final ChatWallpaper SILVER     = new SingleColorChatWallpaper(0xFFA2A2AA, 0f);

  private final @ColorInt int   color;
  private final           float dimLevelInDarkTheme;

  SingleColorChatWallpaper(@ColorInt int color, float dimLevelInDarkTheme) {
    this.color               = color;
    this.dimLevelInDarkTheme = dimLevelInDarkTheme;
  }

  private SingleColorChatWallpaper(Parcel in) {
    color               = in.readInt();
    dimLevelInDarkTheme = in.readFloat();
  }

  @Override
  public float getDimLevelForDarkTheme() {
    return dimLevelInDarkTheme;
  }

  @Override
  public void loadInto(@NonNull ImageView imageView) {
    imageView.setImageDrawable(new ColorDrawable(color));
  }

  @Override
  public boolean isSameSource(@NonNull ChatWallpaper chatWallpaper) {
    if (this == chatWallpaper) return true;
    if (getClass() != chatWallpaper.getClass()) return false;
    SingleColorChatWallpaper that = (SingleColorChatWallpaper) chatWallpaper;

    return color == that.color;
  }

  @Override
  public @NonNull Wallpaper serialize() {
    Wallpaper.SingleColor.Builder builder = Wallpaper.SingleColor.newBuilder();

    builder.setColor(color);

    return Wallpaper.newBuilder()
                    .setSingleColor(builder)
                    .setDimLevelInDarkTheme(dimLevelInDarkTheme)
                    .build();
  }

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeInt(color);
    dest.writeFloat(dimLevelInDarkTheme);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SingleColorChatWallpaper that = (SingleColorChatWallpaper) o;
    return color == that.color && Float.compare(dimLevelInDarkTheme, that.dimLevelInDarkTheme) == 0;
  }

  @Override
  public int hashCode() {
    return Objects.hash(color, dimLevelInDarkTheme);
  }

  public static final Creator<SingleColorChatWallpaper> CREATOR = new Creator<SingleColorChatWallpaper>() {
    @Override
    public SingleColorChatWallpaper createFromParcel(Parcel in) {
      return new SingleColorChatWallpaper(in);
    }

    @Override
    public SingleColorChatWallpaper[] newArray(int size) {
      return new SingleColorChatWallpaper[size];
    }
  };
}
