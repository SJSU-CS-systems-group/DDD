package net.discdd.client.bundletransmission;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Update;

@Dao
public interface RecentTransportDao {
    @Insert
    void insert(RecentTransport recentTransport);

    @Insert
    void insertAll(RecentTransport... recentTransports);

    @Delete
    void delete(RecentTransport recentTransport);

}
