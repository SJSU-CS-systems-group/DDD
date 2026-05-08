package net.discdd.bundleclient.utils;

import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.Upsert;

import java.util.List;

@Dao
public interface RecentTransportDao {

    @Upsert
    void upsert(RecentTransport recentTransport);

    @Query("SELECT * FROM RecentTransports WHERE transportId = :transportId")
    RecentTransport getById(String transportId);

    @Query("SELECT * FROM RecentTransports")
    List<RecentTransport> getAllTransportsSync();

    @Query("DELETE FROM RecentTransports WHERE lastSeen < :expirationThreshold")
    void deleteExpired(long expirationThreshold);
}
