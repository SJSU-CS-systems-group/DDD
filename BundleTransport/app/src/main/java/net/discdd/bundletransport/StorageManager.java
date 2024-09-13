package net.discdd.bundletransport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StorageManager {
    private final Path filePath;
    //For right now, measured in megabytes
    //TODO: handle other byte preferences
    private final long userStoragePreference;

    public StorageManager(Path filePath, long userStoragePreference) {
        this.filePath = filePath;
        this.userStoragePreference = userStoragePreference;
    }

    /**
     * deletes file paths until user storage preference is reached
     *
     * @throws IOException
     */
    public void updateStorage() throws IOException {
        //Create list of all files in transport (downstream AND upstream)
        List<Path> storageList = getStorageList();
        //delete first file and update list until adequate size is reached
        while (storageSize(storageList) > userStoragePreference) {
            Files.delete(storageList.get(0));
            storageList = getStorageList();
        }
    }

    /**
     * gets list of file paths based on which exist in bundle transmission
     *
     * @return
     * @throws IOException
     */
    private List<Path> getStorageList() throws IOException {
        //Create list of all files in transport (downstream AND upstream)
        List<Path> storageList;
        try (Stream<Path> walk = Files.walk(filePath)) {
            storageList = walk.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        //If no file found, return empty list
        if (storageList.isEmpty()) {
            //TODO: inform no bundles to delete
            return storageList;
        }
        //If files found, sort them based on last modified time
        storageList.sort(Comparator.comparing(this::getLastModifiedTime).reversed());
        return storageList;
    }

    /**
     * given a path, return its last modified time. to be used in sorting list of
     * file paths chronologically.
     *
     * @param path
     * @return
     */
    private FileTime getLastModifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            //TODO: unable to find last modified time for file in storage, replacing with 0, will be deleted
            e.printStackTrace();
            return FileTime.fromMillis(0);
        }
    }

    /**
     * given a list of file paths, return the total size of files in list in megabytes
     *
     * @param sList
     * @return
     */
    private long storageSize(List<Path> sList) {
        long s = 0;
        for (Path p : sList) {
            try {
                s += Files.size(p);
            } catch (IOException e) {
                //TODO: more descriptive error
                throw new RuntimeException(e);
            }
        }
        return s;
    }
}
