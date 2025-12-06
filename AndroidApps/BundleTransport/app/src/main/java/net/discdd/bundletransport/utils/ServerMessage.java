package net.discdd.bundletransport.utils;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "ServerMessages")
public class ServerMessage {
    @PrimaryKey(autoGenerate = false)
    private long messageId;
    private String date;
    private String message;
    private boolean read;

    public long getMessageId() {
        return messageId;
    }
    public void setMessageId(long messageId) {
        this.messageId = messageId;
    }
    public String getDate() {
        return date;
    }
    public void setDate(String date) {
        this.date = date;
    }
    public String getMessage() {
        return message;
    }
    public void setMessage(String message) {
        this.message = message;
    }
    public boolean isRead() {
        return read;
    }
    public void setRead(boolean read) {
        this.read = read;
    }
}
