package com.ddd.utils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import com.ddd.model.Acknowledgement;

public class AckRecordUtils {

  public static Acknowledgement readAckRecordFromFile(File inputFile) {
      String bundleId = "";
      try (BufferedReader bufferedReader = new BufferedReader(new FileReader(inputFile))){
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
