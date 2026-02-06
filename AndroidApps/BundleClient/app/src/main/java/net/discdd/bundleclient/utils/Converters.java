package net.discdd.bundleclient.utils;

import androidx.room.TypeConverter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

public class Converters {
    private static final ZoneId SERVER_ZONE = ZoneId.of("America/Los_Angeles");

    @TypeConverter
    public static Long fromLocalDateTime(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZone(SERVER_ZONE).toInstant().toEpochMilli();
    }

    @TypeConverter
    public static LocalDateTime toLocalDateTime(Long timestamp) {
        return timestamp == null ? null :
               Instant.ofEpochMilli(timestamp)
                       .atZone(ZoneId.systemDefault())
                       .toLocalDateTime();
    }
}
