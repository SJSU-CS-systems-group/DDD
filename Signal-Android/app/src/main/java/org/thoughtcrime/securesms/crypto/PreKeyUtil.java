/*
 * Copyright (C) 2013-2018 Open Whisper Systems
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

package org.thoughtcrime.securesms.crypto;

import androidx.annotation.NonNull;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.ecc.ECPrivateKey;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.util.Medium;
import org.thoughtcrime.securesms.crypto.storage.PreKeyMetadataStore;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class PreKeyUtil {

  @SuppressWarnings("unused")
  private static final String TAG = Log.tag(PreKeyUtil.class);

  private static final int  BATCH_SIZE  = 100;
  private static final long ARCHIVE_AGE = TimeUnit.DAYS.toMillis(30);

  public synchronized static @NonNull List<PreKeyRecord> generateAndStoreOneTimePreKeys(@NonNull SignalProtocolStore protocolStore, @NonNull PreKeyMetadataStore metadataStore) {
    Log.i(TAG, "Generating one-time prekeys...");

    List<PreKeyRecord> records        = new LinkedList<>();
    int                preKeyIdOffset = metadataStore.getNextOneTimePreKeyId();

    for (int i = 0; i < BATCH_SIZE; i++) {
      int          preKeyId = (preKeyIdOffset + i) % Medium.MAX_VALUE;
      ECKeyPair    keyPair  = Curve.generateKeyPair();
      PreKeyRecord record   = new PreKeyRecord(preKeyId, keyPair);

      protocolStore.storePreKey(preKeyId, record);
      records.add(record);
    }

    metadataStore.setNextOneTimePreKeyId((preKeyIdOffset + BATCH_SIZE + 1) % Medium.MAX_VALUE);

    return records;
  }

  public synchronized static @NonNull SignedPreKeyRecord generateAndStoreSignedPreKey(@NonNull SignalProtocolStore protocolStore, @NonNull PreKeyMetadataStore metadataStore) {
    return generateAndStoreSignedPreKey(protocolStore, metadataStore, protocolStore.getIdentityKeyPair().getPrivateKey());
  }

  public synchronized static @NonNull SignedPreKeyRecord generateAndStoreSignedPreKey(@NonNull SignalProtocolStore protocolStore,
                                                                                      @NonNull PreKeyMetadataStore metadataStore,
                                                                                      @NonNull ECPrivateKey privateKey)
  {
    Log.i(TAG, "Generating signed prekeys...");

    int                signedPreKeyId = metadataStore.getNextSignedPreKeyId();
    SignedPreKeyRecord record         = generateSignedPreKey(signedPreKeyId, privateKey);

    protocolStore.storeSignedPreKey(signedPreKeyId, record);
    metadataStore.setNextSignedPreKeyId((signedPreKeyId + 1) % Medium.MAX_VALUE);

    return record;
  }

  public synchronized static @NonNull SignedPreKeyRecord generateSignedPreKey(int signedPreKeyId, @NonNull ECPrivateKey privateKey) {
    try {
      ECKeyPair keyPair   = Curve.generateKeyPair();
      byte[]    signature = Curve.calculateSignature(privateKey, keyPair.getPublicKey().serialize());

      return new SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(), keyPair, signature);
    } catch (InvalidKeyException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Finds all of the signed prekeys that are older than the archive age, and archive all but the youngest of those.
   */
  public synchronized static void cleanSignedPreKeys(@NonNull SignalProtocolStore protocolStore, @NonNull PreKeyMetadataStore metadataStore) {
    Log.i(TAG, "Cleaning signed prekeys...");

    int activeSignedPreKeyId = metadataStore.getActiveSignedPreKeyId();
    if (activeSignedPreKeyId < 0) {
      return;
    }

    try {
      long                     now           = System.currentTimeMillis();
      SignedPreKeyRecord       currentRecord = protocolStore.loadSignedPreKey(activeSignedPreKeyId);
      List<SignedPreKeyRecord> allRecords    = protocolStore.loadSignedPreKeys();

      allRecords.stream()
                .filter(r -> r.getId() != currentRecord.getId())
                .filter(r -> (now - r.getTimestamp()) > ARCHIVE_AGE)
                .sorted(Comparator.comparingLong(SignedPreKeyRecord::getTimestamp).reversed())
                .skip(1)
                .forEach(record -> {
                  Log.i(TAG, "Removing signed prekey record: " + record.getId() + " with timestamp: " + record.getTimestamp());
                  protocolStore.removeSignedPreKey(record.getId());
                });
    } catch (InvalidKeyIdException e) {
      Log.w(TAG, e);
    }
  }
}
