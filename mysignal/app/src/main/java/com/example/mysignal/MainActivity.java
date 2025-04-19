package com.example.mysignal;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

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

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    static final Uri CONTENT_URL = Uri.parse("content://net.discdd.provider.datastoreprovider/messages");
    static final String TAG = "ddd_signal";
    EditText receiver, messageText, appName;
    Button insert, delete, view, update, startServiceBtn, getMsgBtn;

    TextView messageListLabel;
    ContentResolver resolver;

    ListView messageList;
    SwipeRefreshLayout swipeRefreshLayout;

    HashMap<String, Integer> aduMetadata = new HashMap<>();

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
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        getMsgBtn = findViewById(R.id.BtnGetMsg);

        //grantUriPermission();
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);

        getMsgBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                getMessages();
            }
        });

        // Swipe to refresh setup
        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                // Call the method to load messages when swipe refresh is triggered
                getMessages();
                swipeRefreshLayout.setRefreshing(false); // Stop the refresh animation
            }
        });

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
                if (deleteMsg() == 1) {
                    Toast.makeText(MainActivity.this, "message deleted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "error deleting message", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private int deleteMsg() {
        return resolver.delete(CONTENT_URL,
                               "deleteAllADUsUpto",
                               new String[] { String.valueOf(aduMetadata.get("lastAdded")) });
    }

    private ArrayList<String> queryResolver() throws NullPointerException, IllegalArgumentException {
        Cursor cursor = resolver.query(CONTENT_URL, RESOLVER_COLUMNS, null, null, null);

        if (cursor == null) {
            throw new NullPointerException("Cursor is null");
        }

        ArrayList<String> messageList = new ArrayList<>(cursor.getCount());
        cursor.moveToNext();
        if (cursor.moveToFirst()) {
            String firstRowJson = cursor.getString(cursor.getColumnIndexOrThrow(RESOLVER_COLUMNS[0]));
            try {
                JSONObject jsonObject = new JSONObject(firstRowJson);
                aduMetadata.put("lastAdded", jsonObject.getInt("lastAduAdded"));
                aduMetadata.put("lastDeleted", jsonObject.getInt("lastAduDeleted"));

            } catch (Exception e) {
                // Handle JSON parsing exceptions
                e.printStackTrace();
                throw new IllegalArgumentException("Failed to parse JSON from cursor row");
            }
        }
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
            createDialog("Error", "No ADUs received yet!", true);
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
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            Toast.makeText(this, "Cannot connect to bundleclient", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Internal error, cannot send", Toast.LENGTH_SHORT).show();
        }

    }
}