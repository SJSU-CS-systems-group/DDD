/**
 * Copyright (C) 2015 Open Whisper Systems
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
package org.thoughtcrime.securesms.providers;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.BuildConfig;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;

public final class MmsBodyProvider extends BaseContentProvider {
  private static final String TAG                = Log.tag(MmsBodyProvider.class);
  private static final String CONTENT_AUTHORITY  = BuildConfig.APPLICATION_ID + ".mms";
  private static final String CONTENT_URI_STRING = "content://" + CONTENT_AUTHORITY + "/mms";
  public  static final Uri    CONTENT_URI        = Uri.parse(CONTENT_URI_STRING);
  private static final int    SINGLE_ROW         = 1;

  private static final UriMatcher uriMatcher;

  static {
    uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    uriMatcher.addURI(CONTENT_AUTHORITY, "mms/#", SINGLE_ROW);
  }

  @Override
  public boolean onCreate() {
    return true;
  }


  private File getFile(Uri uri) {
    long id = Long.parseLong(uri.getPathSegments().get(1));
    return new File(getContext().getCacheDir(), id + ".mmsbody");
  }

  @Override
  public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
    Log.i(TAG, "openFile(" + uri + ", " + mode + ")");

    switch (uriMatcher.match(uri)) {
    case SINGLE_ROW:
      Log.i(TAG, "Fetching message body for a single row...");
      File tmpFile = getFile(uri);

      final int fileMode;
      switch (mode) {
      case "w": fileMode = ParcelFileDescriptor.MODE_TRUNCATE |
                           ParcelFileDescriptor.MODE_CREATE   |
                           ParcelFileDescriptor.MODE_WRITE_ONLY; break;
      case "r": fileMode = ParcelFileDescriptor.MODE_READ_ONLY;  break;
      default:  throw new IllegalArgumentException("requested file mode unsupported");
      }

      Log.i(TAG, "returning file " + tmpFile.getAbsolutePath());
      return ParcelFileDescriptor.open(tmpFile, fileMode);
    }

    throw new FileNotFoundException("Request for bad message.");
  }

  @Override
  public int delete(@NonNull Uri uri, String arg1, String[] arg2) {
    switch (uriMatcher.match(uri)) {
    case SINGLE_ROW:
      return getFile(uri).delete() ? 1 : 0;
    }
    return 0;
  }

  @Override
  public String getType(@NonNull Uri arg0) {
    return null;
  }

  @Override
  public Uri insert(@NonNull Uri arg0, ContentValues arg1) {
    return null;
  }

  @Override
  public Cursor query(@NonNull Uri arg0, String[] arg1, String arg2, String[] arg3, String arg4) {
    return null;
  }

  @Override
  public int update(@NonNull Uri arg0, ContentValues arg1, String arg2, String[] arg3) {
    return 0;
  }
  public static Pointer makeTemporaryPointer(Context context) {
    return new Pointer(context, ContentUris.withAppendedId(MmsBodyProvider.CONTENT_URI, System.currentTimeMillis()));
  }

  public static class Pointer {
    private final Context context;
    private final Uri     uri;

    public Pointer(Context context, Uri uri) {
      this.context = context;
      this.uri = uri;
    }

    public Uri getUri() {
      return uri;
    }

    public OutputStream getOutputStream() throws FileNotFoundException {
      return context.getContentResolver().openOutputStream(uri, "w");
    }

    public InputStream getInputStream() throws FileNotFoundException {
      return context.getContentResolver().openInputStream(uri);
    }

    public void close() {
      context.getContentResolver().delete(uri, null, null);
    }
  }
}
