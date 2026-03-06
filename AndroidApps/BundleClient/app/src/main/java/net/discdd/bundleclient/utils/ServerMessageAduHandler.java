package net.discdd.bundleclient.utils;

import android.content.Context;
import com.google.gson.Gson;
import net.discdd.model.ADU;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
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
        try {
            byte[] bytes = Files.readAllBytes(adu.getSource().toPath());
            AppDatabase.runOnDatabaseExecutor(() -> {
                try {
                    ClientMessagePayload payload = GSON.fromJson(
                            new String(bytes, StandardCharsets.UTF_8), ClientMessagePayload.class);
                    ServerMessage msg = new ServerMessage();
                    msg.setMessageId(payload.messageId);
                    msg.setSentAt(Instant.parse(payload.sentAt));
                    msg.setReceivedAt(Instant.now());
                    msg.setMessage(payload.message);
                    msg.setRead(payload.read);
                    AppDatabase.getInstance(context).serverMessageDao().insert(msg);
                } catch (Exception e) {
                    logger.log(WARNING, "Failed to process server message ADU id=" + adu.getADUId(), e);
                }
            });
        } catch (IOException e) {
            logger.log(WARNING, "Failed to read server message ADU", e);
        }
    }
}
