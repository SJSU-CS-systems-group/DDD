package net.discdd.bundletransport.utils;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "ServerMessages")
public class ServerMessage {
    @PrimaryKey(autoGenerate = false)
    public long messageId;
    public String date;
    public String message;
    public boolean read;
}
