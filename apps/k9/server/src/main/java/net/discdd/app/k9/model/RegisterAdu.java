package net.discdd.app.k9.model;

import net.discdd.grpc.AppDataUnit;

import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

public class RegisterAdu {
    static final Logger logger = Logger.getLogger(RegisterAdu.class.getName());

    final public String[] prefixes, suffixes;
    final public String password;

    public RegisterAdu(String[] prefixes, String[] suffixes, String password) {
        this.prefixes = prefixes;
        this.suffixes = suffixes;
        this.password = password;
    }

    /*
        Register ADU file has the following 4 lines
        where [variable] is used to define a variable:

        register
        [prefix1],[prefix2],[prefix3]
        [suffix1],[suffix2],[suffix3]
        [password]
     */
    public static RegisterAdu parseAdu(AppDataUnit adu) {
        String[] lines = new String(adu.getData().toByteArray()).split("\r?\n");
        if (lines.length != 4) {
            return null;
        }

        String[] prefixes = lines[1].split(",");
        String[] suffixes = lines[2].split(",");
        if (prefixes.length < 1 || suffixes.length < 1) {
            logger.log(SEVERE, "No prefixes or suffixes found in the register ADU");
            return null;
        }

        return new RegisterAdu(prefixes, suffixes, lines[3]);
    }
}
