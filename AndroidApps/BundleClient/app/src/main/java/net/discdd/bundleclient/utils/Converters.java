package net.discdd.bundleclient.utils;

import android.util.Base64;
import androidx.room.TypeConverter;
import net.discdd.grpc.GetRecencyBlobResponse;

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

    @TypeConverter
    public static String fromRecencyBlobResponse(GetRecencyBlobResponse recencyBlobResponse) {
        return recencyBlobResponse == null ? null : Base64.encodeToString(recencyBlobResponse.toByteArray(),
                                                                          Base64.NO_WRAP);
    }

    @TypeConverter
    public static GetRecencyBlobResponse toRecencyBlobResponse(String str) {
        if (str == null) {
            return null;
        }
        try {
            byte[] bytes = Base64.decode(str, Base64.NO_WRAP);
            return GetRecencyBlobResponse.parseFrom(bytes);
        } catch (Exception e) {
            return null;
        }
    }
}
