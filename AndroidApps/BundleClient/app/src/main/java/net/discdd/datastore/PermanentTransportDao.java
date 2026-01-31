package net.discdd.datastore;

import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

public interface PermanentTransportDao {
    @Insert
    void insertOrUpdate(PermanentTransport transport);

    @Insert
    void insertAll(PermanentTransport... transports);

    @Delete
    void delete(PermanentTransport transport);

    @Query("SELECT * FROM PermanentTransport")
    List<PermanentTransport> getAllTransports();

    @Query("SELECT * FROM PermanentTransport WHERE transportId = :transportId")
    PermanentTransport getByTransportId(String transportId);

    @Query("DELETE FROM PermanentTransport WHERE lastSeen < :expirationTime")
    void deleteExpired(long expirationTime);

    @Query("DELETE FROM PermanentTransport WHERE transportId = :transportId")
    void deleteByTransportId(String transportId);

    @Query("UPDATE PermanentTransport SET lastExchange = :timestamp, lastSeen = :timestamp WHERE transportId = :transportId")
    void updateExchangeTimestamp(String transportId, long timestamp);
}
