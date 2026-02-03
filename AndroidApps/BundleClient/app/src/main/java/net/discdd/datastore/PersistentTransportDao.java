package net.discdd.datastore;

import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

public interface PersistentTransportDao {
    @Insert
    void insertAll(PersistentTransport... transports);

    @Delete
    void deleteAll();

    @Query("SELECT * FROM PersistentTransport")
    List<PersistentTransport> getAllTransports();

    @Query("SELECT * FROM PersistentTransport WHERE transportId = :transportId")
    PersistentTransport getByTransportId(String transportId);

    @Query("DELETE FROM PersistentTransport WHERE transportId = :transportId")
    void deleteByTransportId(String transportId);
}
