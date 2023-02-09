package org.thoughtcrime.securesms.migrations;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobs.DownloadLatestEmojiDataJob;
import org.thoughtcrime.securesms.jobs.EmojiSearchIndexDownloadJob;
import org.thoughtcrime.securesms.storage.StorageSyncHelper;

/**
 * Added for when we started syncing contact names in storage service.
 * Rotates the storageId of every system contact and then schedules a storage sync.
 */
public final class StorageServiceSystemNameMigrationJob extends MigrationJob {

  public static final String KEY = "StorageServiceSystemNameMigrationJob";

  StorageServiceSystemNameMigrationJob() {
    this(new Parameters.Builder().build());
  }

  private StorageServiceSystemNameMigrationJob(@NonNull Parameters parameters) {
    super(parameters);
  }

  @Override
  public boolean isUiBlocking() {
    return false;
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void performMigration() {
    SignalDatabase.recipients().markAllSystemContactsNeedsSync();
    StorageSyncHelper.scheduleSyncForDataChange();
  }

  @Override
  boolean shouldRetry(@NonNull Exception e) {
    return false;
  }

  public static class Factory implements Job.Factory<StorageServiceSystemNameMigrationJob> {
    @Override
    public @NonNull StorageServiceSystemNameMigrationJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new StorageServiceSystemNameMigrationJob(parameters);
    }
  }
}
