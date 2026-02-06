package net.discdd.datastore;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Upsert;

import java.util.List;

@Dao
public interface RecentTransportDao {
    @Insert
    void insertAll(RecentTransport... transports);

    @Upsert
    void insertOrUpdate(RecentTransport transport);

    @Query("SELECT * FROM RecentTransports")
    List<RecentTransport> getAllTransports();

    @Query("SELECT * FROM RecentTransports WHERE transportId = :transportId")
    RecentTransport getByTransportId(String transportId);

    @Query("DELETE FROM RecentTransports WHERE transportId = :transportId")
    void deleteByTransportId(String transportId);
}
