package com.example.mysignal;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    static final Uri CONTENT_URL=Uri.parse("content://com.ddd.datastore.providers/messages");
    static final String TAG = "ddd_signal";
    EditText receiver, messageText, appName;
    Button insert, delete, view, update, startServiceBtn;

    TextView messageListLabel;
    ContentResolver resolver;

    private static final String[] RESOLVER_COLUMNS = {"data", "appName"};
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        resolver=getContentResolver();

        receiver=findViewById(R.id.receiver);
        messageText=findViewById(R.id.message);
        appName=findViewById(R.id.app_name);

        insert=findViewById(R.id.btn_insert);
        view=findViewById(R.id.btn_view_messages);
        update=findViewById(R.id.btn_update_status);
        delete=findViewById(R.id.btn_delete);
        startServiceBtn=findViewById(R.id.btn_start_service);
        //grantUriPermission();
        //getMessages();
        insert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                addMessage();
            }
        });
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                getMessages();
            }
        });
        startServiceBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startService(new Intent(getApplicationContext(), SocketService.class));
            }
        });

        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                /*String receiverTXT=receiver.getText().toString();
                String messageTXT=messageText.getText().toString();
                String appNameTXT=appName.getText().toString();
                Boolean checkDeleteStatus=resolver.delete(receiverTXT, messageTXT, appNameTXT);
                if(checkDeleteStatus){
                    Toast.makeText(MainActivity.this, "message deleted", Toast.LENGTH_SHORT);
                }
                else{
                    Toast.makeText(MainActivity.this, "error deleting message", Toast.LENGTH_SHORT);
                }*/
            }
        });
    }

    private ArrayList<String> queryResolver() throws NullPointerException, IllegalArgumentException {
        Log.d(TAG, "cr query: "+resolver);
        Cursor cursor = resolver.query(CONTENT_URL, RESOLVER_COLUMNS, null, null, null);

        Log.d(TAG, "Cursor: "+cursor);
        if (cursor == null) {
            throw new NullPointerException("Cursor is null");
        }

        ArrayList<String> messageList = new ArrayList<>(cursor.getCount());
        cursor.moveToNext();
        while (!cursor.isAfterLast()) {
            messageList.add(cursor.getString(cursor.getColumnIndexOrThrow(RESOLVER_COLUMNS[0])));
            cursor.moveToNext();
        }

        cursor.close();

        return messageList;
    }

    private void createDialog(String title, CharSequence message, boolean cancelable){
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setCancelable(cancelable);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.show();
    }

    public void getMessages(){
        FileStoreHelper fileStoreHelper = new FileStoreHelper(getApplicationContext().getApplicationInfo().dataDir);
        //String messageList="";



        List<byte[]> arr;
        try {
            arr = fileStoreHelper.getAppData();
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            Toast.makeText(this, "Internal error", Toast.LENGTH_SHORT).show();
            return;
        }


        ArrayList<String> messageList;
        try {
            messageList = queryResolver();
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            createDialog("Error", "Error loading messages", true);
            return;
        }

        StringBuilder messages = new StringBuilder();
        for(int i = 0; i < messageList.size(); i++) {
            messages.append((i+1)+". "+messageList.get(i)+"\n");
        }

        createDialog("Message List", messages, true);
    }
    public void addMessage(){
        String message=messageText.getText().toString();

        if (message.isEmpty()) {
            return;
        }

        ContentValues values=new ContentValues();
        values.put(RESOLVER_COLUMNS[0], message.getBytes());
        values.put(RESOLVER_COLUMNS[1], getApplicationContext().getPackageName().getBytes());

        try{
            Log.d(TAG, "cr insert: "+resolver);
            resolver.insert(CONTENT_URL, values);
            getMessages();
        } catch (IllegalArgumentException e){
            Log.e(TAG, e.getMessage());
            Toast.makeText(this, "Cannot connect to bundleclient", Toast.LENGTH_SHORT).show();
        } catch(SecurityException e){
            Log.e(TAG, e.getMessage());
            Toast.makeText(this, "Cannot send", Toast.LENGTH_SHORT).show();
        }

    }
}