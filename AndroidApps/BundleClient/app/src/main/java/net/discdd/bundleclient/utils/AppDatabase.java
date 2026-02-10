package net.discdd.bundleclient.utils;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Database(entities = { ServerMessage.class }, version = 1)
@TypeConverters({ Converters.class })
public abstract class AppDatabase extends RoomDatabase {
    private static final int NUMBER_OF_THREADS = 1;
    private static AppDatabase INSTANCE;

    public static synchronized AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            INSTANCE = Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, "ServerMessages")
                    .fallbackToDestructiveMigration()
                    .build();
        }
        return INSTANCE;
    }

    private static final ExecutorService databaseWriteExecutor = Executors.newFixedThreadPool(NUMBER_OF_THREADS);

    public static void runOnDatabaseExecutor(Runnable task) {
        databaseWriteExecutor.execute(task);
    }

    public abstract ServerMessageDao serverMessageDao();
}
