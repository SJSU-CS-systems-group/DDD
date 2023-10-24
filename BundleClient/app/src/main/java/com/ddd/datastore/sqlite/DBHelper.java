package com.example.contentprovidertest.sqlite;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

public class DBHelper extends SQLiteOpenHelper{
    public DBHelper(@Nullable Context context) {
        super(context, "messages.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase sqLiteDatabase) {
        sqLiteDatabase.execSQL("create Table MessageTable(messageID INT, receiver TEXT, message TEXT, appName TEXT, status TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int i, int i1) {
        sqLiteDatabase.execSQL("drop table if exists MessageTable");
        onCreate(sqLiteDatabase);
    }

    public Boolean insertMessage(String receiver, String message, String appName){
        SQLiteDatabase DB=this.getWritableDatabase();
        ContentValues contentValues=new ContentValues();
        contentValues.put("receiver", receiver);
        contentValues.put("message", message);
        contentValues.put("appName", appName);
        contentValues.put("status", "SENDING");
        long results=DB.insert("MessageTable", null, contentValues);
        return results!=-1;
    }

    public Boolean updateMessageData(String receiver, String message, String appName){
        SQLiteDatabase DB=this.getWritableDatabase();
        ContentValues contentValues=new ContentValues();
        contentValues.put("receiver", receiver);
        contentValues.put("message", message);
        contentValues.put("appName", appName);
        contentValues.put("status", "SENT");
        Cursor cursor=DB.rawQuery("select * from MessageTable where receiver=? and appName=?", new String[]{receiver,appName});
        if(cursor.getCount()>0) {
            long results = DB.update("MessageTable", contentValues, "receiver=? and appName=?", new String[]{receiver,appName});
            return results != -1;
        }
        return false;
    }

    public Boolean deleteMessageData(String receiver, String message, String appName){
        SQLiteDatabase DB=this.getWritableDatabase();
        ContentValues contentValues=new ContentValues();
        contentValues.put("receiver", receiver);
        contentValues.put("message", message);
        contentValues.put("appName", appName);
        Cursor cursor=DB.rawQuery("select * from MessageTable where receiver=? and appName=?", new String[]{receiver,appName});
        if(cursor.getCount()>0) {
            long results = DB.delete("MessageTable", "receiver=? and appName=?", new String[]{receiver,appName});
            return results != -1;
        }
        return false;
    }

    public Cursor getAllMessages(){
        SQLiteDatabase DB=this.getWritableDatabase();
        ContentValues contentValues=new ContentValues();
        Cursor cursor=DB.rawQuery("select * from MessageTable", null);
        return cursor;
    }
}
