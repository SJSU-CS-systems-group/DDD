package net.discdd.datastore.recenttransports;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {RecentTransport.class}, version = 1, exportSchema = false)
public abstract class RecentTransportDatabase extends RoomDatabase {

    private static volatile RecentTransportDatabase INSTANCE;

    public abstract RecentTransportDao recentTransportDao();

    public static RecentTransportDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (RecentTransportDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            RecentTransportDatabase.class,
                            "recent_transport_database"
                    ).build();
                }
            }
        }
        return INSTANCE;
    }
}