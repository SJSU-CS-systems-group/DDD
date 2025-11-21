package net.discdd.bundletransport.utils;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Upsert;

import java.util.List;

@Dao
public interface ServerMessageDao {
    @Insert
    void insert(ServerMessage serverMessage);

    @Upsert
    void insertAll(List<ServerMessage> serverMessages);

    @Query("SELECT * FROM ServerMessages")
    LiveData<List<ServerMessage>> getAllServerMessages();

    @Query("UPDATE ServerMessages SET read = 1 WHERE messageId = :messageId")
    void markRead(int messageId);

    @Query("DELETE FROM ServerMessages WHERE messageId = :messageId")
    void deleteById(int messageId);

    @Query("SELECT COUNT(*) FROM ServerMessages")
    int count();
}
