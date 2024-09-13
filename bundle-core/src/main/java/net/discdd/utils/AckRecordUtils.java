package net.discdd.utils;

import net.discdd.model.Acknowledgement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AckRecordUtils {
    private static final Logger logger = Logger.getLogger(AckRecordUtils.class.getName());

    public static Acknowledgement readAckRecordFromFile(Path ackFilePath) {
        try {
            return new Acknowledgement(new String(Files.readAllBytes(ackFilePath)));
        } catch (IOException e) {
            // we don't do a full stack trace here because this is a common case
            logger.log(Level.WARNING, "Failed to read Acknowledgment record from " + ackFilePath, e);
        }
        return null;
    }

    public static void writeAckRecordToFile(Acknowledgement ackRecord, Path ackFilePath) {
        String bundleId = ackRecord.getBundleId().trim();
        var oldAckRecord = readAckRecordFromFile(ackFilePath);
        // Only update if the file has changed to avoid modifying the timestamp if it doesn't change
        if (oldAckRecord != null && oldAckRecord.getBundleId().equals(bundleId)) {
            logger.log(Level.FINE, "AckRecord already exists for bundleId: " + bundleId);
            return;
        }
        try {
            Files.write(ackFilePath, ackRecord.getBundleId().getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to write Acknowledgment record to " + ackFilePath, e);
        }
    }
}
