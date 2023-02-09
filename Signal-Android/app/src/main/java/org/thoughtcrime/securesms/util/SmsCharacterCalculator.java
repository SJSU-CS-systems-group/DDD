/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.util;

import android.os.Parcel;
import android.telephony.SmsMessage;

import org.signal.core.util.logging.Log;

public class SmsCharacterCalculator extends CharacterCalculator {

  private static final String TAG = Log.tag(SmsCharacterCalculator.class);

  @Override
  public CharacterState calculateCharacters(String messageBody) {
    int[] length;
    int   messagesSpent;
    int   charactersSpent;
    int   charactersRemaining;

    try {
      length              = SmsMessage.calculateLength(messageBody, false);
      messagesSpent       = length[0];
      charactersSpent     = length[1];
      charactersRemaining = length[2];
    } catch (NullPointerException e) {
      Log.w(TAG, e);
      messagesSpent       = 1;
      charactersSpent     = messageBody.length();
      charactersRemaining = 1000;
    } catch (RuntimeException e) {
      if (e instanceof SecurityException || e.getCause() instanceof SecurityException) {
        Log.e(TAG, "Security Exception", e);
        messagesSpent       = 1;
        charactersSpent     = messageBody.length();
        charactersRemaining = 1000;
      } else {
        throw e;
      }
    }

    int maxMessageSize;

    if (messagesSpent > 0) {
      maxMessageSize = (charactersSpent + charactersRemaining) / messagesSpent;
    } else {
      maxMessageSize = (charactersSpent + charactersRemaining);
    }
    
    return new CharacterState(messagesSpent, charactersRemaining, maxMessageSize, maxMessageSize);
  }

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
  }

  public static final Creator<SmsCharacterCalculator> CREATOR = new Creator<SmsCharacterCalculator>() {
    @Override
    public SmsCharacterCalculator createFromParcel(Parcel in) {
      return new SmsCharacterCalculator();
    }

    @Override
    public SmsCharacterCalculator[] newArray(int size) {
      return new SmsCharacterCalculator[size];
    }
  };
}
