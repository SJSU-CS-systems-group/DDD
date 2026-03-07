package net.discdd.bundleclient.utils;

import androidx.room.TypeConverter;

import java.time.Instant;

public class Converters {
    @TypeConverter
    public static Long fromInstant(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    @TypeConverter
    public static Instant toInstant(Long millis) {
        return millis == null ? null : Instant.ofEpochMilli(millis);
    }
}
