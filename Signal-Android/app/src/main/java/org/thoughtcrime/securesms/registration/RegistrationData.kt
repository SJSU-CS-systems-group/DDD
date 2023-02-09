package org.thoughtcrime.securesms.registration

import org.signal.libsignal.zkgroup.profiles.ProfileKey

data class RegistrationData(
  val code: String,
  val e164: String,
  val password: String,
  val registrationId: Int,
  val profileKey: ProfileKey,
  val fcmToken: String?,
  val pniRegistrationId: Int
) {
  val isFcm: Boolean = fcmToken != null
  val isNotFcm: Boolean = fcmToken == null
}
