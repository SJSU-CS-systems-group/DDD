// package com.ddd.server.bundlerouting.WindowUtils;

// import java.io.File;
// import java.io.FileOutputStream;
// import java.io.IOException;
// import java.nio.charset.StandardCharsets;
// import java.security.spec.EncodedKeySpec;
// import java.sql.SQLException;
// import java.util.List;

// import ddd.SNRDatabases;
// import ddd.SecurityUtils;
// import ddd.BundleIDUtils.BundleIDGenerator;
// import ddd.WindowUtils.CircularBuffer;
// import ddd.WindowUtils.WindowExceptions.BufferOverflow;
// import ddd.WindowUtils.WindowExceptions.InvalidBundleID;
// import ddd.WindowUtils.WindowExceptions.InvalidLength;
// import ddd.WindowUtils.WindowExceptions.RecievedInvalidACK;
// import ddd.WindowUtils.WindowExceptions.RecievedOldACK;

// //TODO: Combine with ServerWindow
// public class Window {
//     private int windowLength                = 10; /* Default Value */
//     private CircularBuffer circularBuffer   = null;
//     // private String serverWindowDataPath     = null;
//     // public static final String windowFile   = "window.csv";
//     private SNRDatabases database           = null;
//     private static final String dbTableName = "ServerWindow";
//     private String clientID                 = null;

//     /* Counters are used to maintain range in the window
//      * and are used as Unsigned Long */
//     private long startCounter       = 0;
//     private long endCounter         = 0;

//     public Window(int length, String clientID) throws InvalidLength
//     {
//         // serverWindowDataPath = "E:\\SJSU\\Fall 22\\CS
//         297\\Workspace\\DDD\\app\\src\\test\\resources\\Server\\Clients"+File.separator+clientID;
//         // SecurityUtils.createDirectory(serverWindowDataPath);

//         // TODO: Change to config
//         String url = "jdbc:mysql://localhost:3306";
//         String uname    = "root";
//         String password = "password";
//         String dbName = "SNRDatabase";

//         String dbTableCreateQuery = "CREATE TABLE "+ dbTableName+ " " +
//                                     "(clientID VARCHAR(256) not NULL," +
//                                     "startCounter VARCHAR(256)," +
//                                     "endCounter VARCHAR(256)," +
//                                     "length INTEGER," +
//                                     "PRIMARY KEY (clientID))";

//         try {
//             database = new SNRDatabases(url, uname, password, dbName);
//             database.createTable(dbTableCreateQuery);
//         } catch (SQLException e) {
//             System.out.println("[WIN]: Failed to create database for Server Window!");
//             e.printStackTrace();
//         }

//         try {
//             initializeWindow();
//         } catch (SQLException | BufferOverflow e) {
//             System.out.println(e+"\n[WIN] INFO: Failed to initialize window from database, creating new Window");

//             if (length > 0) {
//                 windowLength = length;
//             } else {
//                 //TODO: Change to log
//                 System.out.printf("Invalid window size, using default size [%d]", windowLength);
//             }

//             circularBuffer = new CircularBuffer(windowLength);
//         }
//         this.clientID = clientID;
//     }

//     private void updateWindowDB()
//     {
//         String updateQuery = "UPDATE "+dbTableName+
//                              " SET startCounter = '"+Long.toUnsignedString(startCounter)+"'"+
//                              ", endCounter = '"+Long.toUnsignedString(endCounter)+"'"+
//                              " WHERE clientID = '"+clientID+"'";

//         try {
//             database.updateEntry(updateQuery);
//         } catch (SQLException e) {
//             System.out.println("[WIN]: Failed to update Server Window DB!");
//             e.printStackTrace();
//         }

//         // String dbFile = serverWindowDataPath + File.separator + windowFile;

//         // try (FileOutputStream stream = new FileOutputStream(dbFile)) {
//         //     String metadata = Long.toUnsignedString(startCounter) + "," + Long.toUnsignedString(endCounter) +
//         "," +circularBuffer.getLength();
//         //     stream.write(metadata.getBytes());
//         // } catch (IOException e) {
//         //     System.out.println("Error: Failed to write Window to file! "+e);
//         // }
//     }

//     private void fillWindow() throws BufferOverflow
//     {
//         for (long i = startCounter; i < endCounter; ++i) {
//             String bundleID = BundleIDGenerator.generateBundleID(clientID, i, BundleIDGenerator.DOWNSTREAM);
//             if (i == startCounter) {
//                 circularBuffer.initializeFromIndex(bundleID, (int) Long.remainderUnsigned(i, windowLength));
//             } else {
//                 circularBuffer.add(bundleID);
//             }
//         }
//     }

//     private void initializeWindow() throws SQLException, InvalidLength, BufferOverflow
//     {
//         String query = "SELECT startCounter, endCounter, length FROM "+dbTableName+
//                         " WHERE clientID = '"+clientID+"'";

//         String[] results = (database.getFromTable(query)).get(0);

//         startCounter = Long.parseLong(results[0]);
//         endCounter   = Long.parseLong(results[1]);
//         windowLength = Integer.parseInt(results[2]);

//         // String dbFile = serverWindowDataPath + File.separator + windowFile;

//         // String dbData = new String(SecurityUtils.readFromFile(dbFile), StandardCharsets.UTF_8);

//         // String[] dbCSV = dbData.split(",");

//         // startCounter = Long.parseLong(dbCSV[0]);
//         // endCounter = Long.parseLong(dbCSV[1]);
//         // windowLength = Integer.parseInt(dbCSV[2]);

//         circularBuffer = new CircularBuffer(windowLength);
//         fillWindow();
//     }

//     public void add(String bundleID) throws BufferOverflow, InvalidBundleID
//     {
//         long bundleIDcounter = BundleIDGenerator.getCounterFromBundleID(bundleID, BundleIDGenerator.DOWNSTREAM);

//         if (endCounter != bundleIDcounter) {
//             throw new InvalidBundleID("Expected: "+Long.toUnsignedString(endCounter)+", Got: "+Long
//             .toUnsignedString(bundleIDcounter));
//         }

//         circularBuffer.add(bundleID);
//         endCounter++;
//         updateWindowDB();
//     }

//     public String getCurrentbundleID(String clientID)
//     {
//         return BundleIDGenerator.generateBundleID(clientID, endCounter, BundleIDGenerator.DOWNSTREAM);
//     }

//     public String getLatestBundleID()
//     {
//         return circularBuffer.getValueAtEnd();
//     }

//     public void moveWindowAhead(long ack) throws InvalidLength, RecievedOldACK, RecievedInvalidACK
//     {
//         compareBundleID(ack);

//         int index = (int) Long.remainderUnsigned(ack, circularBuffer.getLength());
//         circularBuffer.deleteUntilIndex(index);
//         startCounter = ack + 1;
//         updateWindowDB();
//         // TODO: Change to log
//         System.out.println("Updated start: "+startCounter+"; End: "+endCounter);
//     }

//     public void compareBundleID(long ack) throws RecievedOldACK, RecievedInvalidACK
//     {
//         if (Long.compareUnsigned(ack,startCounter) == -1) {
//             throw new RecievedOldACK("Received old ACK [" + Long.toUnsignedString(ack) + " < " + Long
//             .toUnsignedString(startCounter) + "]" );
//         } else if (Long.compareUnsigned(ack,endCounter) == 1) {
//             throw new RecievedInvalidACK("Received Invalid ACK [" + Long.toUnsignedString(ack) + " < " + Long
//             .toUnsignedString(endCounter) + "]" );
//         }
//     }

//     public String[] getWindow()
//     {
//         return circularBuffer.getBuffer();
//     }

//     public boolean isWindowFull()
//     {
//         return circularBuffer.isBufferFull();
//     }
// }
