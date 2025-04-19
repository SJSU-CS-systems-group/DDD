package net.discdd.app.k9.model;

public class LoginAduAck {
    String email, password, message;
    boolean success;

    public LoginAduAck(String email, String password, boolean success, String message) {
        this.email = email;
        this.password = password;
        this.success = success;
        this.message = message;
    }

    public byte[] toByteArray() {
        return String.format("login-ack\n%s\n%s\n%s\n%s",
                             (success ? "success" : "failure"),
                             message != null ? message : "",
                             email != null ? email : "",
                             password != null ? password : "").getBytes();
    }
}
