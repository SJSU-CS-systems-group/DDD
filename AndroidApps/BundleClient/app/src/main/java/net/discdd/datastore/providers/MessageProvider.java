package net.discdd.datastore.providers;

import static android.net.Uri.fromFile;
import static java.lang.String.format;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.discdd.bundleclient.BundleClientWifiDirectService;
import net.discdd.client.bundlesecurity.ClientSecurity;
import net.discdd.utils.StoreADUs;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Logger;

public class MessageProvider extends ContentProvider {

    private static final Logger logger = Logger.getLogger(MessageProvider.class.getName());
    public static final String PROVIDER_NAME = "net.discdd.provider.datastoreprovider";
    public static final Uri URL = Uri.parse("content://" +  PROVIDER_NAME + "/messages");
    public static final int MAX_ADU_SIZE = 512*1024;

    private StoreADUs sendADUsStorage;
    private StoreADUs receiveADUsStorage;

    /**
     * TODO: this FileStoreHelper is used to create App ID if it does not already exist BUT
     * this utility will be moved to ApplicationDataManager, delete this instance once that
     * is done.
     */

    private String getCallerAppId() throws IOException {
        int receiverId = Binder.getCallingUid();
        return getContext().getPackageManager().getNameForUid(receiverId);
    }

    private void checkCallerAppId() throws SecurityException {
        try {
            if (getCallerAppId().startsWith("net.discdd.")) return;
        } catch (IOException e) {
            logger.log(WARNING, "Unable to get caller app ID", e);
        }
        throw new SecurityException("not on the list!");
    }
    @Override
    public boolean onCreate() {
        var appRootDataDir = Paths.get(getContext().getApplicationInfo().dataDir);
        sendADUsStorage = new StoreADUs(appRootDataDir.resolve("send"));
        receiveADUsStorage = new StoreADUs(appRootDataDir.resolve("receive"));
        return true;
    }

    @Nullable
    @Override
    public Cursor query(
            @NonNull Uri uri,
            @Nullable String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        checkCallerAppId();

        MatrixCursor cursor = new MatrixCursor(new String[] { "data", "id", "offset", "exception" });

        try {
            String appId = getCallerAppId();
            if (selection != null) {
                switch (selection) {
                    case "clientId" -> {
                        String clientId = ClientSecurity.getInstance().getClientID();
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
                        long aduId = Long.parseLong(selectionArgs[0]);
                        long offset = selectionArgs.length > 1 ? Long.parseLong(selectionArgs[1]) : 0;
                        // make sure we get the data before we call new row, so that we don't get a partial row if an exception happens
                        byte[] aduData =
                                receiveADUsStorage.getADU(null, appId, aduId, offset, MAX_ADU_SIZE);
                        cursor.newRow().add("data", aduData)
                                .add("id", aduId)
                                .add("offset", offset);
                        return cursor;
                    }
                    default -> {
                        logger.log(SEVERE, format("%s made a request with unknown selection: %s", appId, selection));
                        return null;
                    }
                }
            } else {
                List<StoreADUs.AduIdData> datalist = receiveADUsStorage.getAllAppIdAndData(appId);
                for (StoreADUs.AduIdData adu: datalist) {
                    cursor.newRow().add("data", new String(adu.data())).add("id", adu.id());
                }
            }
        } catch (Exception ex) {
            logger.log(WARNING, "Error getting app data", ex);
            cursor.newRow().add("exception", ex.getMessage());
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
        checkCallerAppId();
        try {
            String appName = getCallerAppId();
            byte[] data = contentValues.getAsByteArray("data");
            Long offset = contentValues.getAsLong("offset");
            Boolean finished = contentValues.getAsBoolean("finished");
            Long aduId = contentValues.getAsLong("aduId");

            logger.log(INFO, format("%s inserting: %s bytes, at %s, %s is finished", appName, data.length, offset, finished));
            var rspUri = fromFile(sendADUsStorage.addADU(null, appName, data,
                                                   aduId == null ? -1 : aduId, offset == null ? 0 : offset, finished));
            var service = BundleClientWifiDirectService.instance;
            if (service != null) {
                service.notifyNewAdu();
            }
            return rspUri;
        } catch (IOException e) {
            logger.log(WARNING, "Unable to add file", e);
            return null;
        }
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        String appName;
        checkCallerAppId();

        try {
            appName = getCallerAppId();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            if ("deleteAllADUsUpto".equals(selection) && selectionArgs != null && selectionArgs.length == 1) {
                long lastProcessedADUId = Long.parseLong(selectionArgs[0]);
                receiveADUsStorage.deleteAllFilesUpTo(null, appName, lastProcessedADUId);
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
        checkCallerAppId();
        // TODO: implement update if necessary
        return rowsUpdated;
    }

}
