package net.discdd.bundleclient;

import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.function.Consumer;

public class LogFragment extends Fragment implements Consumer<String> {
    private TextView logMsgs;
    BundleClientActivity activity;

    public LogFragment(BundleClientActivity activity) {
        this.activity = activity;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable
    Bundle savedInstanceState) {
        var layout = inflater.inflate(R.layout.log_fragment, container, false);
        logMsgs = layout.findViewById(R.id.logmsgs);
        logMsgs.setText(activity.subscribeToLogs(this));
        logMsgs.setMovementMethod(new ScrollingMovementMethod());
        return layout;
    }

    public void accept(String newLog) {
        logMsgs.append(newLog);
        if (logMsgs.getLineCount() > 200) {
            int nl = logMsgs.getText().toString().indexOf('\n');
            logMsgs.getEditableText().delete(0, nl+1);
        }
    }
}
