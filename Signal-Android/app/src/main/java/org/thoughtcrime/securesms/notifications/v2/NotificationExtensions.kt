package org.thoughtcrime.securesms.notifications.v2

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.contacts.avatars.ContactPhoto
import org.thoughtcrime.securesms.contacts.avatars.FallbackContactPhoto
import org.thoughtcrime.securesms.contacts.avatars.GeneratedContactPhoto
import org.thoughtcrime.securesms.contacts.avatars.ProfileContactPhoto
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader.DecryptableUri
import org.thoughtcrime.securesms.mms.GlideApp
import org.thoughtcrime.securesms.notifications.NotificationIds
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.BitmapUtil
import org.thoughtcrime.securesms.util.BlurTransformation
import java.util.concurrent.ExecutionException

fun Drawable?.toLargeBitmap(context: Context): Bitmap? {
  if (this == null) {
    return null
  }

  val largeIconTargetSize: Int = context.resources.getDimensionPixelSize(R.dimen.contact_photo_target_size)

  return BitmapUtil.createFromDrawable(this, largeIconTargetSize, largeIconTargetSize)
}

fun Recipient.getContactDrawable(context: Context): Drawable? {
  val contactPhoto: ContactPhoto? = if (isSelf) ProfileContactPhoto(this) else contactPhoto
  val fallbackContactPhoto: FallbackContactPhoto = if (isSelf) getFallback(context) else fallbackContactPhoto
  return if (contactPhoto != null) {
    try {
      val transforms: MutableList<Transformation<Bitmap>> = mutableListOf()
      if (shouldBlurAvatar()) {
        transforms += BlurTransformation(ApplicationDependencies.getApplication(), 0.25f, BlurTransformation.MAX_RADIUS)
      }
      transforms += CircleCrop()

      GlideApp.with(context.applicationContext)
        .load(contactPhoto)
        .diskCacheStrategy(DiskCacheStrategy.ALL)
        .transform(MultiTransformation(transforms))
        .submit(
          context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width),
          context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height)
        )
        .get()
    } catch (e: InterruptedException) {
      fallbackContactPhoto.asDrawable(context, avatarColor)
    } catch (e: ExecutionException) {
      fallbackContactPhoto.asDrawable(context, avatarColor)
    }
  } else {
    fallbackContactPhoto.asDrawable(context, avatarColor)
  }
}

fun Uri.toBitmap(context: Context, dimension: Int): Bitmap {
  return try {
    GlideApp.with(context.applicationContext)
      .asBitmap()
      .load(DecryptableUri(this))
      .diskCacheStrategy(DiskCacheStrategy.NONE)
      .submit(dimension, dimension)
      .get()
  } catch (e: InterruptedException) {
    Bitmap.createBitmap(dimension, dimension, Bitmap.Config.RGB_565)
  } catch (e: ExecutionException) {
    Bitmap.createBitmap(dimension, dimension, Bitmap.Config.RGB_565)
  }
}

fun Intent.makeUniqueToPreventMerging(): Intent {
  return setData((Uri.parse("custom://" + System.currentTimeMillis())))
}

fun Recipient.getFallback(context: Context): FallbackContactPhoto {
  return GeneratedContactPhoto(getDisplayName(context), R.drawable.ic_profile_outline_40)
}

fun NotificationManager.isDisplayingSummaryNotification(): Boolean {
  if (Build.VERSION.SDK_INT > 23) {
    try {
      return activeNotifications.any { notification -> notification.id == NotificationIds.MESSAGE_SUMMARY }
    } catch (e: Throwable) {
    }
  }
  return false
}
