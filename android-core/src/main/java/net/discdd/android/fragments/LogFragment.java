package net.discdd.android.fragments;

import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.discdd.android_core.R;

import java.util.LinkedList;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class LogFragment extends Fragment implements Consumer<String> {
    private static final Logger logger = Logger.getLogger(LogFragment.class.getName());
    private TextView logMsgs;
    public static LinkedList<String> logRecords;
    public static Consumer<String> logConsumer;

    {
        LogFragment.registerLoggerHandler();
    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        var layout = inflater.inflate(R.layout.log_fragment, container, false);
        logMsgs = layout.findViewById(R.id.logmsgs);
        logMsgs.setText(subscribeToLogs(this));
        logMsgs.setMovementMethod(new ScrollingMovementMethod());
        return layout;
    }

    public void accept(String newLog) {
        getActivity().runOnUiThread(() -> {
            logMsgs.append(newLog);
            if (logMsgs.getLineCount() > 200) {
                int nl = logMsgs.getText().toString().indexOf('\n');
                logMsgs.getEditableText().delete(0, nl + 1);
            }
        });
    }

    private static String subscribeToLogs(Consumer<String> logConsumer) {
        LogFragment.logConsumer = logConsumer;
        return String.join("\n", logRecords);
    }

    private static final Handler logHandler = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            // get the last part of the logger name
            var loggerNameParts = logRecord.getLoggerName().split("\\.");
            var loggerName = loggerNameParts[loggerNameParts.length - 1];
            if (LogFragment.logRecords.size() > 100) LogFragment.logRecords.remove(0);
            String entry = String.format("[%s] %s", loggerName, logRecord.getMessage());
            System.out.println(entry);
            if (LogFragment.logConsumer != null) LogFragment.logConsumer.accept(entry + '\n');
            LogFragment.logRecords.add(entry);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() throws SecurityException {
        }
    };

    public static void registerLoggerHandler() {
        if (LogFragment.logRecords == null) {
            Logger.getLogger("").addHandler(logHandler);
            LogFragment.logRecords = new LinkedList<>();
            LogFragment.logRecords.add("Log messages:\n");
        }
    }
}
