package net.discdd.bundletransport.utils;

import android.app.Application;
import androidx.lifecycle.LiveData;

import java.util.List;

public class ServerMessageRepository {
    private ServerMessageDao serverMessageDao;

    public ServerMessageRepository(Application application) {
        AppDatabase db = AppDatabase.getInstance(application);
        serverMessageDao = db.serverMessageDao();
    }

    public LiveData<List<ServerMessage>> getAllServerMessages() {
        return serverMessageDao.getAllServerMessages();
    }

    public void insert(ServerMessage serverMessage) {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            serverMessageDao.insert(serverMessage);
        });
    }

    public void insertAll(List<ServerMessage> serverMessages) {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            serverMessageDao.insertAll(serverMessages);
        });
    }

    public void markRead(long messageId) {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            serverMessageDao.markRead(messageId);
        });
    }

    public void deleteById(long messageId) {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            serverMessageDao.deleteById(messageId);
        });
    }

    public void seedSampleMessages(List<ServerMessage> sampleMessages) {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            if(serverMessageDao.count() == 0) {
                serverMessageDao.insertAll(sampleMessages);
            }
        });
    }
}
