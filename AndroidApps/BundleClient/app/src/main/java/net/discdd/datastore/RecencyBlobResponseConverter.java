package net.discdd.datastore;

import android.util.Base64;
import androidx.room.TypeConverter;
import com.google.protobuf.InvalidProtocolBufferException;
import net.discdd.grpc.GetRecencyBlobResponse;

public class RecencyBlobResponseConverter {
    @TypeConverter
    public static String fromRecencyBlobResponse(GetRecencyBlobResponse response) {
        if (response == null) {
            return null;
        }
        return Base64.encodeToString(response.toByteArray(), Base64.NO_WRAP);
    }

    @TypeConverter
    public static GetRecencyBlobResponse toRecencyBlobResponse(String str) {
        if (str == null) {
            return null;
        }
        try {
            byte[] bytes = Base64.decode(str, Base64.NO_WRAP);
            return GetRecencyBlobResponse.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            return null;
        }
    }
}
