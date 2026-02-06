package net.discdd.datastore;

import androidx.room.TypeConverter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

public class Converters {
    @TypeConverter
    public static Long fromLocalDateTime(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @TypeConverter
    public static LocalDateTime toLocalDateTime(Long timestamp) {
        return timestamp == null ?
               null :
               LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
    }
}
