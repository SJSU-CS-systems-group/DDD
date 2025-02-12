package net.discdd.app.k9.model;

import net.discdd.grpc.AppDataUnit;

public class LoginAdu {
    private String email, password;

    public LoginAdu(String email, String password) {
        this.email = email;
        this.password = password;
    }

    public String getEmail() {
        return this.email;
    }

    public String getPassword() {
        return this.password;
    }

    public static LoginAdu parseAdu(AppDataUnit adu) {
        String[] lines = new String(adu.getData().toByteArray()).split("\r?\n");
        if (lines.length != 3) {
            return null;
        }

        return new LoginAdu(lines[1], lines[2]);
    }
}
