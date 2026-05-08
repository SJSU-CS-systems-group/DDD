package net.discdd.bundleclient.utils;

import android.util.Base64;
import androidx.room.TypeConverter;
import net.discdd.grpc.GetRecencyBlobResponse;

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
