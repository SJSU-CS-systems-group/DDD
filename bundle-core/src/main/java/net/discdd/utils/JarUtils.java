package net.discdd.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.logging.Logger;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

import static java.util.logging.Level.SEVERE;

public class JarUtils {
    private static final Logger logger = Logger.getLogger(JarUtils.class.getName());

    private static long getChecksum(File file) throws IOException {
        long checkSum = 0L;
        try (CheckedInputStream checkedInputStream = new CheckedInputStream(new FileInputStream(file), new CRC32())) {
            byte[] buffer = new byte[1024];
            while (checkedInputStream.read(buffer) != -1) {}
            checkSum = checkedInputStream.getChecksum().getValue();
        }
        return checkSum;
    }

    private static void addFilesToJar(File file, JarOutputStream jarOutputStream, String path, Manifest manifest) throws
            IOException {
        // If the file is a directory, recursively add its contents to the JAR output stream
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            for (File childFile : files) {
                addFilesToJar(childFile, jarOutputStream, path + file.getName() + "/", manifest);
            }
        }
        // If the file is a file, add it to the JAR output stream and update the manifest
        else {

            MessageDigest messageDigest = null;
            try {
                messageDigest = MessageDigest.getInstance("SHA-256");
                // Create a new JAR entry for the file
                JarEntry jarEntry = new JarEntry(path + file.getName());
                jarEntry.setSize(file.length());
                jarEntry.setCrc(getChecksum(file));
                jarOutputStream.putNextEntry(jarEntry);

                // Copy the contents of the file to the JAR output stream
                FileInputStream fileInputStream = new FileInputStream(file);
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                    jarOutputStream.write(buffer, 0, bytesRead);
                    messageDigest.update(buffer, 0, bytesRead);
                }
                fileInputStream.close();

                // Update the manifest with the checksum of the file
                Attributes attributes = manifest.getAttributes(path + file.getName());
                if (attributes == null) {
                    attributes = new Attributes();
                    manifest.getEntries().put(path + file.getName(), attributes);
                }
                attributes.putValue("SHA-256-Digest", Base64.getEncoder().encodeToString(messageDigest.digest()));

                // Close the JAR entry
                jarOutputStream.closeEntry();

                // Reset the message digest for the next file
                messageDigest.reset();

            } catch (NoSuchAlgorithmException e) {
                logger.log(SEVERE, "Failed to calculate hash of JAR entries using SHA-256", e);
            }
        }
    }

    public static void dirToJar(String dirPath, String jarFilePath) {
        try {
            FileOutputStream fos = new FileOutputStream(jarFilePath);
            JarOutputStream jos = new JarOutputStream(fos);

            Manifest manifest = new Manifest();
            File dir = new File(dirPath);
            if (dir.isDirectory()) {
                File[] files = dir.listFiles();
                for (File childFile : files) {
                    addFilesToJar(childFile, jos, "", manifest);
                }
            }

            JarEntry manifestEntry = new JarEntry("META-INF/MANIFEST.MF");
            jos.putNextEntry(manifestEntry);
            manifest.write(jos);
            jos.closeEntry();

            jos.close();
            fos.close();
        } catch (IOException e) {
            logger.log(SEVERE, "Failed to JAR directory " + dirPath + " to " + jarFilePath, e);
        }
    }

    public static void jarToDir(String jarFilePath, String dirPath) {
        try {
            JarFile jarFile = new JarFile(jarFilePath);

            // Get the manifest from the JAR file
            Manifest manifest = jarFile.getManifest();

            // Create the destination folder if it doesn't exist
            File destinationFolder = new File(dirPath);
            if (!destinationFolder.exists()) {
                destinationFolder.mkdirs();
            }

            // Iterate over the entries in the JAR file and extract them
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();

                // Skip the manifest file, as it was already extracted
                if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                    continue;
                }

                // Create the destination file
                File destinationFile = new File(destinationFolder, entry.getName());

                // Create any necessary subdirectories
                if (!destinationFile.getParentFile().exists()) {
                    destinationFile.getParentFile().mkdirs();
                }

                // Extract the file
                MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
                InputStream inputStream = jarFile.getInputStream(entry);
                FileOutputStream outputStream = new FileOutputStream(destinationFile);
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    messageDigest.update(buffer, 0, bytesRead);
                }
                inputStream.close();
                outputStream.close();

                // Verify the checksum of the extracted file
                String fileChecksum = Base64.getEncoder().encodeToString(messageDigest.digest());
                String manifestChecksum = manifest.getEntries().get(entry.getName()).getValue("SHA-256-Digest");
                if (!fileChecksum.equals(manifestChecksum)) {
                    throw new SecurityException("Checksum verification failed for " + entry.getName());
                }
            }

            // Close the JAR file
            jarFile.close();
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to unjar file " + jarFilePath + " to " + dirPath, e);
        }
    }
}
