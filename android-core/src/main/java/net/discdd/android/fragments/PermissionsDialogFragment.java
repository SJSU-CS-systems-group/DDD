package net.discdd.android.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import net.discdd.android_core.R;

public class PermissionsDialogFragment extends DialogFragment {
    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setMessage(R.string.dialog_message)
                .setNeutralButton(R.string.dialog_btn_text,
                                  (dialog, id) -> System.out.println("dialog dismissed."));
        return builder.create();
    }
}
