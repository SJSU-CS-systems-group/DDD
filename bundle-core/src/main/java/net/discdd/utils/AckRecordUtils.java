package net.discdd.utils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.discdd.model.Acknowledgement;

public class AckRecordUtils {
    private static final Logger logger = Logger.getLogger(AckRecordUtils.class.getName());

    public static Acknowledgement readAckRecordFromFile(File inputFile) {
        logger.log(Level.FINE, "reading Acknowledgment record from file");
        String bundleId = "";
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(inputFile))) {
            String line = "";
            while ((line = bufferedReader.readLine()) != null) {
                bundleId = line.trim();
            }
            return new Acknowledgement(bundleId);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void writeAckRecordToFile(Acknowledgement ackRecord, File ackFile) {
        String bundleId = ackRecord.getBundleId();
        try (BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(ackFile))) {
            bufferedWriter.write(bundleId);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
