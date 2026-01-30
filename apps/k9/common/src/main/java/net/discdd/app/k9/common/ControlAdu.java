package net.discdd.app.k9.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Properties;

/**
 * this interface uses properties to convert bytes to and from Adu records.
 * the records are stored as a property file that starts with a comment "# CONTROL".
 */
public class ControlAdu {
    public static final Map<String, Class<? extends ControlAdu>> ADU_TYPES = Map.of("login",
                                                                                    LoginControlAdu.class,
                                                                                    "login-ack",
                                                                                    LoginAckControlAdu.class,
                                                                                    "register",
                                                                                    RegisterControlAdu.class,
                                                                                    "register-ack",
                                                                                    RegisterAckControlAdu.class,
                                                                                    "whoami",
                                                                                    WhoAmIControlAdu.class,
                                                                                    "whoami-ack",
                                                                                    WhoAmIAckControlAdu.class);
    // The header for control ADUs, used to identify them in the byte stream.
    // start with a # to make it compatible with properties files
    public static final String CONTROL_HEADER = "# CONTROL";
    // ugly hack to turn off strict method checking when creating ControlAdu instances
    // from deserialized properties.
    final protected Properties properties = new Properties();

    ControlAdu(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return;
        }
        ;
        for (var entry : map.entrySet()) {
            // make sure the method exists
            String key = entry.getKey();
            var value = entry.getValue();
            properties.put(key, value.toString());
        }
    }

    public static boolean isControlAdu(byte[] bytes) {
        if (bytes == null || bytes.length < CONTROL_HEADER.length()) {
            return false;
        }
        for (int i = 0; i < CONTROL_HEADER.length(); i++) {
            if (bytes[i] != CONTROL_HEADER.charAt(i)) {
                return false;
            }
        }
        return bytes[CONTROL_HEADER.length()] == '\n';
    }

    public static ControlAdu fromBytes(byte[] bytes) throws IOException {
        if (!isControlAdu(bytes)) {
            throw new IOException("Not a control ADU");
        }
        var props = new Properties();
        try (var is = new ByteArrayInputStream(bytes)) {
            props.load(is);
        }
        String type = props.getProperty("type");
        if (type == null || !ADU_TYPES.containsKey(type)) {
            throw new IOException("Unknown ADU type: " + type);
        }
        try {
            var clazz = ADU_TYPES.get(type);
            Constructor<? extends ControlAdu> ctor = clazz.getConstructor(Map.class);
            return ctor.newInstance(props);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException |
                 InstantiationException e) {
            throw new IOException("Failed to create ADU from bytes", e);
        }
    }

    public byte[] toBytes() throws IOException {
        String typeKey = null;
        for (var entry : ADU_TYPES.entrySet()) {
            if (entry.getValue().isInstance(this)) {
                typeKey = entry.getKey();
                break;
            }
        }
        if (typeKey == null) {
            throw new IOException("Unknown ADU type: " + this.getClass().getName());
        }
        properties.put("type", typeKey);
        var baos = new ByteArrayOutputStream();
        baos.writeBytes(CONTROL_HEADER.getBytes());
        baos.write('\n');
        properties.store(baos, null);
        return baos.toByteArray();
    }

    @Override
    public String toString() {
        var sos = new ByteArrayOutputStream();
        try {
            sos.writeBytes(CONTROL_HEADER.getBytes());
            sos.write('\n');
            properties.store(sos, null);
            return sos.toString();
        } catch (IOException e) {
            return "ControlAdu{error=" + e.getMessage() + "}";
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ControlAdu)) return false;
        ControlAdu that = (ControlAdu) obj;
        return properties.equals(that.properties);
    }

    @Override
    public int hashCode() {
        return properties.hashCode();
    }

    public interface EmailAck {
        String email();

        boolean success();

        String message();
    }

    public static class LoginControlAdu extends ControlAdu {
        public LoginControlAdu(Map<String, Object> properties) {
            super(properties);
        }

        public String email() {
            return properties.getProperty("email");
        }

        public String password() {
            return properties.getProperty("password");
        }
    }

    public static class LoginAckControlAdu extends ControlAdu implements EmailAck {
        public LoginAckControlAdu(Map<String, Object> properties) {
            super(properties);
        }

        public String email() {
            return properties.getProperty("email");
        }

        public boolean success() {
            return Boolean.parseBoolean(properties.getProperty("success"));
        }

        public String message() {
            return properties.getProperty("message");
        }
    }

    public static class RegisterControlAdu extends ControlAdu {
        public RegisterControlAdu(Map<String, Object> properties) {
            super(properties);
        }

        public String prefix() {
            return properties.getProperty("prefix");
        }

        public String suffix() {
            return properties.getProperty("suffix");
        }

        public String password() {
            return properties.getProperty("password");
        }
    }

    public static class RegisterAckControlAdu extends ControlAdu implements EmailAck {
        public RegisterAckControlAdu(Map<String, Object> properties) {
            super(properties);
        }

        public String email() {
            return properties.getProperty("email");
        }

        public boolean success() {
            return Boolean.parseBoolean(properties.getProperty("success"));
        }

        public String message() {
            return properties.getProperty("message");
        }
    }

    public static class WhoAmIControlAdu extends ControlAdu {
        public WhoAmIControlAdu() {
            super(null);
        }

        // we need this one for deserialization
        public WhoAmIControlAdu(Map<String, Object> properties) {
            super(properties);
        }
    }

    public static class WhoAmIAckControlAdu extends ControlAdu implements EmailAck {
        public WhoAmIAckControlAdu(Map<String, Object> properties) {
            super(properties);
        }

        @Override
        public String email() {
            return properties.getProperty("email");
        }

        @Override
        public boolean success() {
            return Boolean.parseBoolean(properties.getProperty("success"));
        }

        @Override
        public String message() {
            return properties.getProperty("message");
        }
    }

}