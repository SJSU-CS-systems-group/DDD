package com.example.mysignal;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.util.Log;

import org.whispersystems.signalservice.internal.websocket.WebSocketProtos;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class SocketListener extends Thread{
    static final Uri CONTENT_URL=Uri.parse("content://com.example.contentprovidertest.providers/messages");
    private static final String TAG = "com.example.mysignal.SocketListener";

    private static String serverIP = "127.0.0.1";
    private static int port = 4444;
    private InetAddress serverAddr = null;
    private Socket sock = null;
    private boolean running = false;
    ContentResolver resolver;
    private static final String OUTPUT_HEADERS = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html\r\n" +
            "Content-Length: ";

    public SocketListener(ContentResolver cr){
        resolver = cr;
    }

    /*public void send(MessageCustom _msg) {
        if (out != null) {
            try {
                out.writeObject(_msg);
                out.flush();

                Log.i("Send Method", "Outgoing : " + _msg.toString());
            } catch (IOException ex) {
                Log.e("Send Method", ex.toString());
            }
        }
    }*/

    public void stopClient() {
        Log.v(TAG,"stopClient method run");
        running = false;
    }

    public byte[] listToArr(List<Byte> list){
        byte[] arr=new byte[list.size()];
        for(int i=0;i<list.size();i++) arr[i]=list.get(i);
        return arr;
    }

    public String getHeaderToArray(InputStream inputStream) {

        String headerTempData = "";

        // chain the InputStream to a Reader
        Reader reader = new InputStreamReader(inputStream);
        try {
            int c;
            while ((c = reader.read()) != -1) {
                //System.out.print((char) c);
                headerTempData += (char) c;

                if (headerTempData.contains("\r\n\r\n"))
                    break;
            }
        } catch (IOException ex) {
            System.err.println(ex.getMessage());
        }

        return headerTempData;
    }

    public byte[] getBody(InputStream inputStream) {

        String bodyTempData = "";
        List<Byte> bodyDataArr = new ArrayList<>();

        // chain the InputStream to a Reader
        Reader reader = new InputStreamReader(inputStream);
        try {
            int c;
            while ((c = reader.read()) != -1) {
                //System.out.print((char) c);
                bodyTempData += (char) c;
                bodyDataArr.add((byte)c);
            }
        } catch (IOException ex) {
            System.err.println(ex.getMessage());
        }

        return listToArr(bodyDataArr);
    }

    public void saveToDB(InputStream inStream, DataOutputStream outStream, Socket socket){
        try{
            try {


                //String headerData = getHeaderToArray(inStream);
                byte[] bodyData = getBody(inStream);
                //System.out.println("headers: "+headerData);
                WebSocketProtos.WebSocketRequestMessage messageObj = WebSocketProtos.WebSocketRequestMessage.parseFrom(bodyData);
                Log.d("deepakSocket","message path: " + messageObj.getPath());

                outStream.write(OUTPUT_HEADERS.getBytes("UTF-8"), 0, OUTPUT_HEADERS.length());
                //out.flush();
                //sendFile(absolutePath+"index.html", out);
                //Log.d("deepakSocket","message header: "+headerData);
                for(int i=0;i<messageObj.getHeadersList().size();i++){
                    Log.d("deepakSocket","message header"+i+": "+messageObj.getHeaders(i));
                }
                Log.d("deepakSocket","message: "+bodyData);
                String receiverName="kapeed";
                String message=bodyData.toString();
                String appNameText="mysignal";

                ContentValues values=new ContentValues();
                values.put("receiver", receiverName);
                values.put("message", message);
                values.put("appName", appNameText);

                resolver.insert(CONTENT_URL, values);

                if(inStream!=null) inStream.close();
                if(outStream!=null) outStream.close();
            } catch (MalformedURLException ex) {
                System.err.println(socket.getLocalAddress() + " is not a parseable URL");
                ex.printStackTrace();
                Log.e("deepakSocket", ex.getMessage());
            } catch (IOException ex) {
                System.err.println(ex.getMessage());
                ex.printStackTrace();
                Log.e("deepakSocket", ex.getMessage());
            }

            // close connection
            socket.close();
        }catch(Exception e){e.printStackTrace();}
    }

    @Override
    public void run() {
        running = true;
        Socket		  socket   = null;
        ServerSocket	server   = null;
        DataInputStream in	   =  null;
        try {

            Log.i("TCP Client", "C: Connecting...");
            try
            {
                server = new ServerSocket(port);
                System.out.println("Server started");

                System.out.println("Waiting for a client ...");

                while(true){
                    socket = server.accept();
                    InputStream inStream;
                    DataOutputStream out;
                    inStream = socket.getInputStream();
                    out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                    saveToDB(inStream, out, socket);
                }
            }
            catch(IOException i)
            {
                System.out.println(i);
            }
        } catch (Exception e) {
            Log.e(TAG, "C: Error", e);
        }
    }
}
