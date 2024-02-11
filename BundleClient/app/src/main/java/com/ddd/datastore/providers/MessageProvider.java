package com.ddd.datastore.providers;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Binder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ddd.datastore.filestore.FileStoreHelper;
import com.example.contentprovidertest.sqlite.DBHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MessageProvider extends ContentProvider {
    public static final String PROVIDER_NAME="com.ddd.datastore.providers";

    public static final String URL="content://"+PROVIDER_NAME+"/messages";

    public static final Uri CONTENT_URI=Uri.parse(URL);
    public static final String receiver="receiver";
    public static final String message="message";
    public static final String appName="appName";
    public static final int uriCode=1;

    private static HashMap<String, String> values;
    static final UriMatcher uriMatcher;
    private FileStoreHelper sendFileStoreHelper;
    private FileStoreHelper receiveFileStoreHelper;

    static{
        uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        uriMatcher.addURI(PROVIDER_NAME, "messages", uriCode);
    }

    private SQLiteDatabase sqlDB;
    static final String DATABASE_NAME="messages";
    static final String TABLE_NAME="messageTable";
    static final int DATABASE_VERSION=1;
    static final String CREATE_DB_TABLE="CREATE TABLE "+TABLE_NAME+" (messageID INT, receiver TEXT, messageBody TEXT, messageHeader TEXT, appName TEXT, status TEXT)";

    @Override
    public boolean onCreate() {
        DBHelper dbHelper=new DBHelper(getContext());
        sqlDB=dbHelper.getWritableDatabase();
        sendFileStoreHelper = new FileStoreHelper(getContext().getApplicationInfo().dataDir+"/send", getContext().getApplicationInfo().dataDir);
        receiveFileStoreHelper = new FileStoreHelper(getContext().getApplicationInfo().dataDir+"/receive", getContext().getApplicationInfo().dataDir);
        if(sqlDB!=null) return true;
        return false;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        MatrixCursor cursor = null;
        try {
            //Log.d("bundleclient", "selection-" + selection);
            //Log.d("bundleclient", "selectionArgs-" + selectionArgs[0]);
            //selection = app name
            //selectionsArgs[0] = app name value
            byte[] res = sendFileStoreHelper.getNextAppData(selectionArgs[0]);
            cursor = new MatrixCursor(new String[]{"data"});
            List<byte[]> arr = new ArrayList<>();
            if (res == null) {
                return cursor;
            }
            arr.add(res);
            cursor.addRow(arr);
        }catch (Exception ex){
            ex.printStackTrace();
            Log.e("bundleclient", ex.getMessage());
        }
        return cursor;
        /*SQLiteQueryBuilder queryBuilder=new SQLiteQueryBuilder();
        queryBuilder.setTables(TABLE_NAME);
        switch ((uriMatcher.match(uri))){
            case uriCode:
                queryBuilder.setProjectionMap(values);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI "+uri);
        }
        Cursor cursor=queryBuilder.query(sqlDB, projection, selection, selectionArgs, null, null, sortOrder);
        cursor.setNotificationUri(getContext().getContentResolver(), uri);
        return cursor;*/
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        switch ((uriMatcher.match(uri))){
            case uriCode:
                return "vnd.android.cursor.dir/messages";
            default:
                throw new IllegalArgumentException("unsupported URI "+uri);
        }
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues contentValues) {
        String appName = contentValues.get("appName").toString();
        Log.d("bundleclient", "cv appName: "+appName);
        int receiverId = Binder.getCallingUid();
        appName = getContext().getPackageManager().getNameForUid(receiverId);
        Log.d("bundleclient", "getNameforUid appName: "+appName);
        //String destination = contentValues.get("destination").toString();

        //destination = "APP";
        String data = contentValues.get("data").toString();
        Log.d("bundleclient", "cv data: "+data);
        try{
            return sendFileStoreHelper.addFile(appName, data.getBytes());
        } catch (IOException e) {
            Log.e("bundleclient", "Unable to add file, error: " + e.getMessage());
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
    public int update(@NonNull Uri uri, @Nullable ContentValues contentValues, @Nullable String selection, @Nullable String[] selectionArgs) {
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
