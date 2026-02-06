package net.discdd.datastore;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = { RecentTransport.class }, version = 1)
public abstract class ClientDataBase extends RoomDatabase {

    private static ClientDataBase INSTANCE;

    public static synchronized ClientDataBase getInstance(Context context) {
        if (INSTANCE == null) {
            INSTANCE = Room.databaseBuilder(context.getApplicationContext(), ClientDataBase.class, "ClientDataBase")
                    .fallbackToDestructiveMigration()
                    .build();
        }

        return INSTANCE;
    }

    public abstract RecentTransportDao transportDao();
}
