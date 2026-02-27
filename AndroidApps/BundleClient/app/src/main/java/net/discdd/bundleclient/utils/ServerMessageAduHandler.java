package net.discdd.bundleclient.utils;

import android.content.Context;
import android.util.Log;
import com.google.gson.Gson;
import net.discdd.model.ADU;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;

public class ServerMessageAduHandler {
    public static final String APP_ID = "net.discdd.bundleclient";
    private static final Logger logger = Logger.getLogger(ServerMessageAduHandler.class.getName());
    private static final Gson GSON = new Gson();

    private static final class ClientMessagePayload {
        int v;
        long messageId;
        String sentAt;
        String message;
        boolean read;
    }

    public static void handle(Context context, ADU adu) {
        Log.d("ServerMessageAduHandler", "handle() called, aduId=" + adu.getADUId() + " file=" + adu.getSource());
        try {
            byte[] bytes = Files.readAllBytes(adu.getSource().toPath());
            Log.d("ServerMessageAduHandler", "read " + bytes.length + " bytes: " + new String(bytes, StandardCharsets.UTF_8));
            AppDatabase.runOnDatabaseExecutor(() -> {
                try {
                    ClientMessagePayload payload = GSON.fromJson(
                            new String(bytes, StandardCharsets.UTF_8), ClientMessagePayload.class);
                    Log.d("ServerMessageAduHandler", "inserting messageId=" + payload.messageId + " msg=" + payload.message);
                    ServerMessage msg = new ServerMessage();
                    msg.setMessageId(payload.messageId);
                    msg.setDate(LocalDateTime.ofInstant(Instant.parse(payload.sentAt), ZoneId.systemDefault()));
                    msg.setMessage(payload.message);
                    msg.setRead(payload.read);
                    AppDatabase.getInstance(context).serverMessageDao().insert(msg);
                    Log.d("ServerMessageAduHandler", "insert complete for messageId=" + payload.messageId);
                } catch (Exception e) {
                    logger.log(WARNING, "Failed to process server message ADU", e);
                }
            });
        } catch (IOException e) {
            logger.log(WARNING, "Failed to read server message ADU", e);
        }
    }
}
