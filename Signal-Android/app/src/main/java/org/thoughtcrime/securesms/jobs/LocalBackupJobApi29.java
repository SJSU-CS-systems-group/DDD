package org.thoughtcrime.securesms.jobs;


import android.annotation.SuppressLint;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.documentfile.provider.DocumentFileHelper;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.signal.core.util.Stopwatch;
import org.signal.core.util.ThreadUtil;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.backup.BackupEvent;
import org.thoughtcrime.securesms.backup.BackupFileIOError;
import org.thoughtcrime.securesms.backup.BackupPassphrase;
import org.thoughtcrime.securesms.backup.BackupVerifier;
import org.thoughtcrime.securesms.backup.FullBackupExporter;
import org.thoughtcrime.securesms.crypto.AttachmentSecretProvider;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.service.GenericForegroundService;
import org.thoughtcrime.securesms.service.NotificationController;
import org.thoughtcrime.securesms.util.BackupUtil;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Backup Job for installs requiring Scoped Storage.
 *
 * @see LocalBackupJob#enqueue(boolean)
 */
public final class LocalBackupJobApi29 extends BaseJob {

  public static final String KEY = "LocalBackupJobApi29";

  private static final String TAG = Log.tag(LocalBackupJobApi29.class);

  public static final String TEMP_BACKUP_FILE_PREFIX = ".backup";
  public static final String TEMP_BACKUP_FILE_SUFFIX = ".tmp";

  private static final int MAX_STORAGE_ATTEMPTS = 5;

  private static final long[] WAIT_FOR_SCOPED_STORAGE = new long[] {
      TimeUnit.SECONDS.toMillis(0),
      TimeUnit.SECONDS.toMillis(2),
      TimeUnit.SECONDS.toMillis(10),
      TimeUnit.SECONDS.toMillis(20),
      TimeUnit.SECONDS.toMillis(30)
  };

  LocalBackupJobApi29(@NonNull Parameters parameters) {
    super(parameters);
  }

  @Override
  public @NonNull Data serialize() {
    return Data.EMPTY;
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException {
    Log.i(TAG, "Executing backup job...");

    BackupFileIOError.clearNotification(context);

    if (!BackupUtil.isUserSelectionRequired(context)) {
      throw new IOException("Wrong backup job!");
    }

    Uri backupDirectoryUri = SignalStore.settings().getSignalBackupDirectory();
    if (backupDirectoryUri == null || backupDirectoryUri.getPath() == null) {
      throw new IOException("Backup Directory has not been selected!");
    }

    ProgressUpdater updater = new ProgressUpdater(context.getString(R.string.LocalBackupJob_verifying_signal_backup));

    NotificationController notification = null;
    try {
      notification = GenericForegroundService.startForegroundTask(context,
                                                                  context.getString(R.string.LocalBackupJob_creating_signal_backup),
                                                                  NotificationChannels.getInstance().BACKUPS,
                                                                  R.drawable.ic_signal_backup);
    } catch (UnableToStartException e) {
      Log.w(TAG, "Unable to start foreground backup service, continuing without service");
    }

    try {
      updater.setNotification(notification);
      EventBus.getDefault().register(updater);
      if (notification != null) {
        notification.setIndeterminateProgress();
      }

      String       backupPassword  = BackupPassphrase.get(context);
      DocumentFile backupDirectory = DocumentFile.fromTreeUri(context, backupDirectoryUri);
      String       timestamp       = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(new Date());
      String       fileName        = String.format("signal-%s.backup", timestamp);

      if (backupDirectory == null || !backupDirectory.canWrite()) {
        BackupFileIOError.ACCESS_ERROR.postNotification(context);
        throw new IOException("Cannot write to backup directory location.");
      }

      deleteOldTemporaryBackups(backupDirectory);

      if (backupDirectory.findFile(fileName) != null) {
        throw new IOException("Backup file already exists!");
      }

      String       temporaryName = String.format(Locale.US, "%s%s%s", TEMP_BACKUP_FILE_PREFIX, UUID.randomUUID(), TEMP_BACKUP_FILE_SUFFIX);
      DocumentFile temporaryFile = backupDirectory.createFile("application/octet-stream", temporaryName);

      if (temporaryFile == null) {
        throw new IOException("Failed to create temporary backup file.");
      }

      if (backupPassword == null) {
        throw new IOException("Backup password is null");
      }

      try {
        Stopwatch   stopwatch     = new Stopwatch("backup-export");
        BackupEvent finishedEvent = FullBackupExporter.export(context,
                                                              AttachmentSecretProvider.getInstance(context).getOrCreateAttachmentSecret(),
                                                              SignalDatabase.getBackupDatabase(),
                                                              temporaryFile,
                                                              backupPassword,
                                                              this::isCanceled);
        stopwatch.split("backup-create");

        boolean valid = verifyBackup(backupPassword, temporaryFile, finishedEvent);

        stopwatch.split("backup-verify");
        stopwatch.stop(TAG);

        if (valid) {
          renameBackup(fileName, temporaryFile);
        } else {
          BackupFileIOError.VERIFICATION_FAILED.postNotification(context);
        }
        EventBus.getDefault().post(finishedEvent);
      } catch (FullBackupExporter.BackupCanceledException e) {
        EventBus.getDefault().post(new BackupEvent(BackupEvent.Type.FINISHED, 0, 0));
        Log.w(TAG, "Backup cancelled");
        throw e;
      } catch (IOException e) {
        Log.w(TAG, "Error during backup!", e);
        EventBus.getDefault().post(new BackupEvent(BackupEvent.Type.FINISHED, 0, 0));
        BackupFileIOError.postNotificationForException(context, e);
        throw e;
      } finally {
        DocumentFile fileToCleanUp = backupDirectory.findFile(temporaryName);
        if (fileToCleanUp != null) {
          if (fileToCleanUp.delete()) {
            Log.w(TAG, "Backup failed. Deleted temp file");
          } else {
            Log.w(TAG, "Backup failed. Failed to delete temp file " + temporaryName);
          }
        }
      }

      BackupUtil.deleteOldBackups();
    } finally {
      if (notification != null) {
        notification.close();
      }
      EventBus.getDefault().unregister(updater);
      updater.setNotification(null);
    }
  }

  private boolean verifyBackup(String backupPassword, DocumentFile temporaryFile, BackupEvent finishedEvent) throws FullBackupExporter.BackupCanceledException {
    Boolean valid    = null;
    int     attempts = 0;

    while (attempts < MAX_STORAGE_ATTEMPTS && valid == null && !isCanceled()) {
      ThreadUtil.sleep(WAIT_FOR_SCOPED_STORAGE[attempts]);

      try (InputStream cipherStream = context.getContentResolver().openInputStream(temporaryFile.getUri())) {
        try {
          valid = BackupVerifier.verifyFile(cipherStream, backupPassword, finishedEvent.getCount(), this::isCanceled);
        } catch (IOException e) {
          Log.w(TAG, "Unable to verify backup", e);
          valid = false;
        }
      } catch (IOException e) {
        attempts++;
        Log.w(TAG, "Unable to find backup file, attempt: " + attempts + "/" + MAX_STORAGE_ATTEMPTS);
      }
    }

    if (isCanceled()) {
      throw new FullBackupExporter.BackupCanceledException();
    }

    return valid != null ? valid : false;
  }

  @SuppressLint("NewApi")
  private void renameBackup(String fileName, DocumentFile temporaryFile) throws IOException {
    int attempts = 0;

    while (attempts < MAX_STORAGE_ATTEMPTS && !DocumentFileHelper.renameTo(context, temporaryFile, fileName)) {
      ThreadUtil.sleep(WAIT_FOR_SCOPED_STORAGE[attempts]);
      attempts++;
      Log.w(TAG, "Unable to rename backup file, attempt: " + attempts + "/" + MAX_STORAGE_ATTEMPTS);
    }

    if (attempts >= MAX_STORAGE_ATTEMPTS) {
      Log.w(TAG, "Failed to rename temp file");
      throw new IOException("Renaming temporary backup file failed!");
    }
  }

  private static void deleteOldTemporaryBackups(@NonNull DocumentFile backupDirectory) {
    for (DocumentFile file : backupDirectory.listFiles()) {
      if (file.isFile()) {
        String name = file.getName();
        if (name != null && name.startsWith(TEMP_BACKUP_FILE_PREFIX) && name.endsWith(TEMP_BACKUP_FILE_SUFFIX)) {
          if (file.delete()) {
            Log.w(TAG, "Deleted old temporary backup file");
          } else {
            Log.w(TAG, "Could not delete old temporary backup file");
          }
        }
      }
    }
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception e) {
    return false;
  }

  @Override
  public void onFailure() {
  }

  private static class ProgressUpdater {
    private final String                 verifyProgressTitle;
    private       NotificationController notification;
    private       boolean                verifying = false;

    public ProgressUpdater(String verifyProgressTitle) {
      this.verifyProgressTitle = verifyProgressTitle;
    }

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onEvent(BackupEvent event) {
      if (notification == null) {
        return;
      }

      if (event.getType() == BackupEvent.Type.PROGRESS || event.getType() == BackupEvent.Type.PROGRESS_VERIFYING) {
        if (event.getEstimatedTotalCount() == 0) {
          notification.setIndeterminateProgress();
        } else {
          notification.setProgress(100, (int) event.getCompletionPercentage());
          if (event.getType() == BackupEvent.Type.PROGRESS_VERIFYING && !verifying) {
            notification.replaceTitle(verifyProgressTitle);
            verifying = true;
          }
        }
      }
    }

    public void setNotification(@Nullable NotificationController notification) {
      this.notification = notification;
    }
  }

  public static class Factory implements Job.Factory<LocalBackupJobApi29> {
    @Override
    public @NonNull
    LocalBackupJobApi29 create(@NonNull Parameters parameters, @NonNull Data data) {
      return new LocalBackupJobApi29(parameters);
    }
  }
}
