package com.ddd.utils;

import com.ddd.model.Acknowledgement;

import java.io.*;
import java.nio.file.Files;

public class AckRecordUtils {

    public static Acknowledgement readAckRecordFromFile(File inputFile) {
        try {
            String bundleId = Files.readString(inputFile.toPath());
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
