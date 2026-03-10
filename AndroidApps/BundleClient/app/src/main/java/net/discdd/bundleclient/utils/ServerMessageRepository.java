package net.discdd.bundleclient.utils;

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
        AppDatabase.runOnDatabaseExecutor(() -> {
            serverMessageDao.insert(serverMessage);
        });
    }

    public void insertAll(List<ServerMessage> serverMessages) {
        AppDatabase.runOnDatabaseExecutor(() -> {
            serverMessageDao.insertAll(serverMessages);
        });
    }

    public void markRead(long messageId) {
        AppDatabase.runOnDatabaseExecutor(() -> {
            serverMessageDao.markRead(messageId);
        });
    }

    public void deleteById(long messageId) {
        AppDatabase.runOnDatabaseExecutor(() -> {
            serverMessageDao.deleteById(messageId);
        });
    }
}
