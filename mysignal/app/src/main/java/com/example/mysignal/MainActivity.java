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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    static final Uri CONTENT_URL = Uri.parse("content://com.ddd.provider.datastoreprovider/messages");
    static final String TAG = "ddd_signal";
    EditText receiver, messageText, appName;
    Button insert, delete, view, update, startServiceBtn;

    TextView messageListLabel;
    ContentResolver resolver;

    ListView messageList;

    private static final String[] RESOLVER_COLUMNS = { "data" };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        resolver = getContentResolver();

        //receiver=findViewById(R.id.receiver);
        messageText = findViewById(R.id.message);
        //appName=findViewById(R.id.app_name);

        insert = findViewById(R.id.btn_insert);
        //view=findViewById(R.id.btn_view_messages);
        messageList = findViewById(R.id.message_list);
        update = findViewById(R.id.btn_update_status);
        delete = findViewById(R.id.btn_delete);
        startServiceBtn = findViewById(R.id.btn_start_service);
        //grantUriPermission();
        getMessages();
        insert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                addMessage();
            }
        });

        /*
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                getMessages();
            }
        });
         */
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
        Cursor cursor = resolver.query(CONTENT_URL, RESOLVER_COLUMNS, null, null, null);

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

    private void createDialog(String title, CharSequence message, boolean cancelable) {
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setCancelable(cancelable);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.show();
    }

    public void getMessages() {
        ArrayList<String> messages;
        try {
            messages = queryResolver();
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            createDialog("Error", "Error loading messages", true);
            return;
        }

        ArrayAdapter<String> messagesAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, messages);
        messageList.setAdapter(messagesAdapter);
    }

    public void addMessage() {
        String message = messageText.getText().toString();

        if (message.isEmpty()) {
            return;
        }

        ContentValues values = new ContentValues();
        values.put(RESOLVER_COLUMNS[0], message.getBytes());

        try {
            Uri uri = resolver.insert(CONTENT_URL, values);
            if (uri == null) {
                throw new Exception("Message not inserted");
            }
            messageText.setText("");
            getMessages();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            Toast.makeText(this, "Cannot connect to bundleclient", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Internal error, cannot send", Toast.LENGTH_SHORT).show();
        }

    }
}