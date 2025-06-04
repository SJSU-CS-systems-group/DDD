package net.discdd.bundletransport;

import android.content.Context;
import android.util.Log;

import fi.iki.elonen.NanoHTTPD;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

public class FileHttpServer extends NanoHTTPD {
    private static final Logger logger = Logger.getLogger(FileHttpServer.class.getName());
    private final File filesDir;

    public FileHttpServer(int port, File filesDir) {
        super(port);
        this.filesDir = filesDir;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        logger.log(INFO, "Serving request: " + uri);

        // For security, restrict access to only the APK files
        if (uri.equals("/ddd-mail.apk") || uri.equals("/DDDClient.apk")) {
            File file = new File(filesDir, uri.substring(1));
            if (file.exists()) {
                try {
                    FileInputStream fis = new FileInputStream(file);
                    return newChunkedResponse(Response.Status.OK,
                                              getMimeTypeForFile(uri), fis);
                } catch (FileNotFoundException e) {
                    logger.log(SEVERE, "File not found: " + file.getAbsolutePath(), e);
                    return newFixedLengthResponse(Response.Status.NOT_FOUND,
                                                  MIME_PLAINTEXT, "File not found");
                }
            } else {
                return newFixedLengthResponse(Response.Status.NOT_FOUND,
                                              MIME_PLAINTEXT, "File not found");
            }
        } else {
            // Serve a simple HTML index page
            StringBuilder html = new StringBuilder();
            html.append("<html><body>");
            html.append("<h1>DDD File Server</h1>");
            html.append("<p>Available files:</p>");
            html.append("<ul>");
            File mailApk = new File(filesDir, "ddd-mail.apk");
            File clientApk = new File(filesDir, "DDDClient.apk");

            if (mailApk.exists()) {
                html.append("<li><a href='/ddd-mail.apk'>DDD Mail App</a></li>");
            }

            if (clientApk.exists()) {
                html.append("<li><a href='/DDDClient.apk'>DDD Client App</a></li>");
            }

            html.append("</ul></body></html>");

            return newFixedLengthResponse(html.toString());
        }
    }
}