package net.discdd.bundleclient.utils;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.Upsert;

import java.util.List;

@Dao
public interface ServerMessageDao {
    @Upsert
    void insert(ServerMessage serverMessage);

    @Upsert
    void insertAll(List<ServerMessage> serverMessages);

    @Query("SELECT * FROM ServerMessages ORDER BY sentAt DESC")
    LiveData<List<ServerMessage>> getAllServerMessages();

    @Query("UPDATE ServerMessages SET read = 1 WHERE messageId = :messageId")
    void markRead(long messageId);

    @Query("DELETE FROM ServerMessages WHERE messageId = :messageId")
    void deleteById(long messageId);
}
