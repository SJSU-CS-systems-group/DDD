package net.discdd.datastore.recenttransports;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface RecentTransportDao {
    @Insert
    void insertOrUpdate(RecentTransport transport);

    @Insert
    void insertAll(RecentTransport... transports);

    @Delete
    void delete(RecentTransport transport);

    @Query("SELECT * FROM RecentTransports")
    List<RecentTransport> getAllTransports();

    @Query("SELECT * FROM RecentTransports WHERE transportId = :transportId")
    RecentTransport getByTransportId(String transportId);

    @Query("DELETE FROM RecentTransports WHERE lastSeen < :expirationTime")
    void deleteExpired(long expirationTime);

    @Query("DELETE FROM RecentTransports WHERE transportId = :transportId")
    void deleteByTransportId(String transportId);

    @Query("UPDATE RecentTransports SET lastExchange = :timestamp, lastSeen = :timestamp WHERE transportId = :transportId")
    void updateExchangeTimestamp(String transportId, long timestamp);
}
