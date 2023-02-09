package org.thoughtcrime.securesms.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import androidx.annotation.NonNull;

import org.signal.core.util.logging.Log;

import java.util.concurrent.atomic.AtomicReference;

public final class NotificationController implements AutoCloseable,
                                                     ServiceConnection
{
  private static final String TAG = Log.tag(NotificationController.class);

  private final Context context;
  private final int     id;

  private int     progress;
  private int     progressMax;
  private boolean indeterminate;
  private long    percent = -1;
  private boolean isBound;

  private final AtomicReference<GenericForegroundService> service = new AtomicReference<>();

  NotificationController(@NonNull Context context, int id) {
    this.context = context;
    this.id      = id;

    isBound = bindToService();
  }

  private boolean bindToService() {
    return context.bindService(new Intent(context, GenericForegroundService.class), this, Context.BIND_AUTO_CREATE);
  }

  public int getId() {
    return id;
  }

  @Override
  public void close() {
    try {
      if (isBound) {
        context.unbindService(this);
        isBound = false;
      } else {
        Log.w(TAG, "Service was not bound at the time of close()...");
      }

      GenericForegroundService.stopForegroundTask(context, id);
    } catch (IllegalArgumentException e) {
      Log.w(TAG, "Failed to unbind service...", e);
    }
  }

  public void setIndeterminateProgress() {
    setProgress(0, 0, true);
  }

  public void setProgress(long newProgressMax, long newProgress) {
    setProgress((int) newProgressMax, (int) newProgress, false);
  }

  public void replaceTitle(@NonNull String title) {
    GenericForegroundService genericForegroundService = service.get();

    if (genericForegroundService == null) return;

    genericForegroundService.replaceTitle(id, title);
  }

  private synchronized void setProgress(int newProgressMax, int newProgress, boolean indeterminant) {
    int newPercent = newProgressMax != 0 ? 100 * newProgress / newProgressMax : -1;

    boolean same = newPercent == percent && indeterminate == indeterminant;

    percent       = newPercent;
    progress      = newProgress;
    progressMax   = newProgressMax;
    indeterminate = indeterminant;

    if (same) return;

    updateProgressOnService();
  }

  private synchronized void updateProgressOnService() {
    GenericForegroundService genericForegroundService = service.get();

    if (genericForegroundService == null) return;

    genericForegroundService.replaceProgress(id, progressMax, progress, indeterminate);
  }

  @Override
  public void onServiceConnected(ComponentName name, IBinder service) {
    Log.i(TAG, "Service connected " + name);

    GenericForegroundService.LocalBinder binder                   = (GenericForegroundService.LocalBinder) service;
    GenericForegroundService             genericForegroundService = binder.getService();

    this.service.set(genericForegroundService);

    updateProgressOnService();
  }

  @Override
  public void onServiceDisconnected(ComponentName name) {
    Log.i(TAG, "Service disconnected " + name);

    service.set(null);
  }
}
