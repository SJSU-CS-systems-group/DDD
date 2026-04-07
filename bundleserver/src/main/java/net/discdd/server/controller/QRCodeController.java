package net.discdd.server.controller;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

@RestController
public class QRCodeController {
    private static final Logger logger = Logger.getLogger(QRCodeController.class.getName());
    private static final int QR_SIZE = 400;

    @Value("${bundle-server.bundle-security.server-serverkeys-path}")
    private String serverKeysPath;

    @Value("${ssl-grpc.server.port:7778}")
    private int grpcPort;

    @GetMapping(value = "/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getQRCode(HttpServletRequest request) {
        try {
            String identity = readKeyFromPem(Path.of(serverKeysPath, "server_identity.pub"));
            String signedPre = readKeyFromPem(Path.of(serverKeysPath, "server_signed_pre.pub"));
            String ratchet = readKeyFromPem(Path.of(serverKeysPath, "server_ratchet.pub"));

            String host = request.getServerName();
            String url = String.format("https://discdd.net/srvr?host=%s&port=%d&identity=%s&signedpre=%s&ratchet=%s",
                                       host,
                                       grpcPort,
                                       identity,
                                       signedPre,
                                       ratchet);

            byte[] pngBytes = generateQRCodePng(url);
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(pngBytes);
        } catch (IOException e) {
            logger.log(SEVERE, "Failed to read server key files", e);
            return ResponseEntity.internalServerError().build();
        } catch (WriterException e) {
            logger.log(SEVERE, "Failed to generate QR code", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private String readKeyFromPem(Path pemFile) throws IOException {
        var lines = Files.readAllLines(pemFile);
        if (lines.size() != 3) {
            throw new IOException("Invalid PEM file format: " + pemFile);
        }
        return lines.get(1);
    }

    private byte[] generateQRCodePng(String content) throws WriterException, IOException {
        var writer = new QRCodeWriter();
        var bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE);
        var outputStream = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);
        return outputStream.toByteArray();
    }
}
