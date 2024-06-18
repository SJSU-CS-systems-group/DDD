package com.ddd.bundletransport.utils;

import com.google.firebase.crashlytics.buildtools.reloc.org.apache.commons.io.IOUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.whispersystems.libsignal.ecc.ECPublicKey;

import android.util.Base64;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SecurityUtils {

    public static String generateID(String publicKeyPath) throws IOException, InvalidKeyException,
            NoSuchAlgorithmException {
        byte[] publicKey = decodePublicKeyfromFile(publicKeyPath);
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] hashedKey = md.digest(publicKey);
        return Base64.encodeToString(hashedKey, Base64.URL_SAFE | Base64.NO_WRAP);
    }

    public static void createEncodedPublicKeyFile(ECPublicKey publicKey, String path) throws IOException {
        String encodedKey = "-----BEGIN EC PUBLIC KEY-----\n";
        try (FileOutputStream stream = new FileOutputStream(path)) {
            encodedKey += Base64.encodeToString(publicKey.serialize(), Base64.URL_SAFE | Base64.NO_WRAP);
            encodedKey += "\n" + "-----END EC PUBLIC KEY-----";
            stream.write(encodedKey.getBytes());
        }
    }

    public static byte[] decodePublicKeyfromFile(String path) throws IOException, InvalidKeyException {
        File file = new File(path.trim());
        InputStream in = new FileInputStream(file);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        List<String> encodedKeyList = new ArrayList<String>();
        String line = reader.readLine();
        while (line != null) {
            encodedKeyList.add(line);
            line = reader.readLine();
        }

        if (encodedKeyList.size() != 3) {
            throw new InvalidKeyException("Error: Invalid Public Key Length");
        }

        if ((encodedKeyList.get(0).equals("-----BEGIN EC PUBLIC KEY-----")) &&
                (encodedKeyList.get(2).equals("-----END EC PUBLIC KEY-----"))) {
            return Base64.decode(encodedKeyList.get(1), Base64.URL_SAFE | Base64.NO_WRAP);
        }

        throw new InvalidKeyException("Error: Invalid Public Key Format");
    }
}
