package net.discdd.datastore.providers;

import static android.net.Uri.fromFile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Binder;

import java.io.File;
import java.nio.file.Paths;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.discdd.client.bundlesecurity.ClientSecurity;
import net.discdd.datastore.sqlite.DBHelper;
import net.discdd.utils.StoreADUs;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

public class MessageProvider extends ContentProvider {

    private static final Logger logger = Logger.getLogger(MessageProvider.class.getName());

    public static final String PROVIDER_NAME = "net.discdd.provider.datastoreprovider";

    public static final String URL = "content://" + PROVIDER_NAME + "/messages";

    public static final Uri CONTENT_URI = Uri.parse(URL);
    public static final String receiver = "receiver";
    public static final String message = "message";
    public static final String appName = "appName";
    public static final int uriCode = 1;

    private static HashMap<String, String> values;
    static final UriMatcher uriMatcher;

    private StoreADUs sendADUsStorage;
    private StoreADUs receiveADUsStorage;

    /**
     * TODO: this FileStoreHelper is used to create App ID if it does not already exist BUT
     * this utility will be moved to ApplicationDataManager, delete this instance once that
     * is done.
     */

    static {
        uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        uriMatcher.addURI(PROVIDER_NAME, "messages", uriCode);
    }

    private SQLiteDatabase sqlDB;
    static final String DATABASE_NAME = "messages";
    static final String TABLE_NAME = "messageTable";
    static final int DATABASE_VERSION = 1;
    static final String CREATE_DB_TABLE = "CREATE TABLE " + TABLE_NAME +
            " (messageID INT, receiver TEXT, messageBody TEXT, messageHeader TEXT, appName TEXT, status TEXT)";

    private String getCallerAppId() throws IOException {
        int receiverId = Binder.getCallingUid();
        String appId = getContext().getPackageManager().getNameForUid(receiverId);
        return appId;
    }

    @Override
    public boolean onCreate() {
        DBHelper dbHelper = new DBHelper(getContext());
        sqlDB = dbHelper.getWritableDatabase();
        sendADUsStorage = new StoreADUs(Paths.get(getContext().getApplicationInfo().dataDir).resolve("send"), true);
        receiveADUsStorage = new StoreADUs(Paths.get(getContext().getApplicationInfo().dataDir).resolve("receive"), false);
        if (sqlDB != null) return true;
        return false;
    }

    @Nullable
    @Override
    public Cursor query(
            @NonNull Uri uri,
            @Nullable String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        MatrixCursor cursor;

        try {
            String appId = getCallerAppId();
            List<byte[]> datalist = receiveADUsStorage.getAllAppData(appId);
            cursor = new MatrixCursor(new String[] { "data" });
            if (selectionArgs != null && selectionArgs.length != 0 && "clientId".equals(selectionArgs[0])) {
                cursor.newRow().add("data", ClientSecurity.getInstance().getClientID());
                return cursor;
            } else {
                for (byte[] data : datalist) {
                    cursor.newRow().add("data", new String(data));
                }
            }
        } catch (Exception ex) {
            logger.log(WARNING, "Error getting app data", ex);
            cursor = null;
        }
        return cursor;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        switch ((uriMatcher.match(uri))) {
            case uriCode:
                return "vnd.android.cursor.dir/messages";
            default:
                throw new IllegalArgumentException("unsupported URI " + uri);
        }
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues contentValues) {
        try {
            String appName = getCallerAppId();
            byte[] data = contentValues.getAsByteArray("data");
            logger.log(INFO, "inserting: " + new String(data));
            return fromFile(sendADUsStorage.addADU(null, appName, data, -1));
        } catch (IOException e) {
            logger.log(WARNING, "Unable to add file", e);
            return null;
        }
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        int rowsDeleted = 0;

        // Used to match uris with Content Providers
        switch (uriMatcher.match(uri)) {
            case uriCode:
                rowsDeleted = sqlDB.delete(TABLE_NAME, selection, selectionArgs);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        // getContentResolver provides access to the content model
        // notifyChange notifies all observers that a row was updated
        getContext().getContentResolver().notifyChange(uri, null);
        return rowsDeleted;
    }

    @Override
    public int update(
            @NonNull Uri uri,
            @Nullable ContentValues contentValues, @Nullable String selection, @Nullable String[] selectionArgs) {
        int rowsUpdated = 0;

        // Used to match uris with Content Providers
        switch (uriMatcher.match(uri)) {
            case uriCode:

                // Update the row or rows of data
                rowsUpdated = sqlDB.update(TABLE_NAME, contentValues, selection, selectionArgs);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        // getContentResolver provides access to the content model
        // notifyChange notifies all observers that a row was updated
        getContext().getContentResolver().notifyChange(uri, null);
        return rowsUpdated;
    }
}
