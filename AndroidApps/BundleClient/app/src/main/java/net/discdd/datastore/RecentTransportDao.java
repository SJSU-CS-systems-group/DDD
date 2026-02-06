package net.discdd.datastore;

import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

public interface RecentTransportDao {
    @Insert
    void insertAll(RecentTransport... transports);

    @Delete
    void deleteAll();

    @Query("SELECT * FROM RecentTransport")
    List<RecentTransport> getAllTransports();

    @Query("SELECT * FROM RecentTransport WHERE transportId = :transportId")
    RecentTransport getByTransportId(String transportId);

    @Query("DELETE FROM RecentTransport WHERE transportId = :transportId")
    void deleteByTransportId(String transportId);

    void insert(RecentTransport transport);
}
