package net.discdd.app.k9.model;

public class RegisterAduAck {
    String email, password, message;
    boolean success;

    public RegisterAduAck(String email, String password, boolean success, String message) {
        this.email = email;
        this.password = password;
        this.success = success;
        this.message = message;
    }

    public byte[] toByteArray() {
        return String.format("register-ack\n%s\n%s\n%s\n%s",
                             (success ? "success" : "failure"),
                             message != null ? message : "",
                             email != null ? email : "",
                             password != null ? password : "").getBytes();
    }
}