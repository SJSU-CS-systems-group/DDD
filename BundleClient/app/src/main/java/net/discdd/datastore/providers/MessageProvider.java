package net.discdd.datastore.providers;

import static android.net.Uri.fromFile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;

import java.nio.file.Paths;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.discdd.client.bundlesecurity.ClientSecurity;
import net.discdd.utils.StoreADUs;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

public class MessageProvider extends ContentProvider {

    private static final Logger logger = Logger.getLogger(MessageProvider.class.getName());
    public static final String PROVIDER_NAME = "net.discdd.provider.datastoreprovider";
    public static final String URL = "content://" + PROVIDER_NAME + "/messages";

    private StoreADUs sendADUsStorage;
    private StoreADUs receiveADUsStorage;

    /**
     * TODO: this FileStoreHelper is used to create App ID if it does not already exist BUT
     * this utility will be moved to ApplicationDataManager, delete this instance once that
     * is done.
     */

    private String getCallerAppId() throws IOException {
        int receiverId = Binder.getCallingUid();
        String appId = getContext().getPackageManager().getNameForUid(receiverId);
        return appId;
    }

    @Override
    public boolean onCreate() {
        var appRootDataDir = Paths.get(getContext().getApplicationInfo().dataDir);
        sendADUsStorage = new StoreADUs(appRootDataDir.resolve("send"));
        receiveADUsStorage = new StoreADUs(appRootDataDir.resolve("receive"));
        logger.info("MessageProvider initialized successfully");
        return true;
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
            cursor = new MatrixCursor(new String[] { "data" });
            if (selection != null) {
                String clientId = ClientSecurity.getInstance().getClientID();
                switch (selection) {
                    case "clientId" -> {
                        cursor.newRow().add("data", clientId);
                        return cursor;
                    }
                    case "aduIds" -> {
                        List<Long> aduIds = receiveADUsStorage.getAllADUIds(appId);
                        for (long id : aduIds) {
                            cursor.newRow().add("data", id);
                        }
                        return cursor;
                    }
                    case "aduData" -> {
                        assert selectionArgs != null;
                        cursor.newRow().add("data", receiveADUsStorage.getADU(appId, Long.parseLong(selectionArgs[0])));
                        return cursor;
                    }
                }
            } else {
                List<byte[]> datalist = receiveADUsStorage.getAllAppData(appId);
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
        return "vnd.android.cursor.dir/messages";
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
        String appName = null;
        try {
            appName = getCallerAppId();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            if ("deleteAllADUsUpto".equals(selection) && selectionArgs != null && selectionArgs.length == 1) {
                long lastProcessedADUId = Long.parseLong(selectionArgs[0]);
                receiveADUsStorage.deleteAllFilesUpTo(null, appName, lastProcessedADUId);
                getContext().getContentResolver().notifyChange(uri, null);
                return 1;
            }
            return 0;
        } catch (Exception e) {
            logger.log(SEVERE, "Error while deleting processed ADUs for app: " + appName, e);
            return 0;
        }
    }

    @Override
    public int update(
            @NonNull Uri uri,
            @Nullable ContentValues contentValues, @Nullable String selection, @Nullable String[] selectionArgs) {
        int rowsUpdated = 0;

        // TODO: implement update if necessary

        // getContentResolver provides access to the content model
        // notifyChange notifies all observers that a row was updated
        getContext().getContentResolver().notifyChange(uri, null);
        return rowsUpdated;
    }

}
