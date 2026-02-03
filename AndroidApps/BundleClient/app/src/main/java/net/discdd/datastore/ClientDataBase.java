package net.discdd.datastore;

import androidx.room.Database;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import org.jetbrains.annotations.NotNull;

@Database(entities = { PersistentTransport.class }, version = 1)
public abstract class ClientDataBase extends RoomDatabase {
    public abstract PersistentTransportDao transportDao();

}
