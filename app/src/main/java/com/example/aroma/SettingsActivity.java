package com.example.aroma;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    private EditText usernameEdit;
    private EditText passwordEdit;
    private EditText portEdit;
    private CheckBox autoStartCheckbox;
    private CheckBox saveTokenCheckbox;
    private Spinner folderSpinner;
    private Spinner themeSpinner;
    private Button showPasswordButton;
    private CredentialsManager credentialsManager;
    private boolean passwordVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        credentialsManager = new CredentialsManager(this);
        usernameEdit = findViewById(R.id.username_edit);
        passwordEdit = findViewById(R.id.password_edit);
        portEdit = findViewById(R.id.port_edit);
        autoStartCheckbox = findViewById(R.id.auto_start_checkbox);
        saveTokenCheckbox = findViewById(R.id.save_token_checkbox);
        folderSpinner = findViewById(R.id.folder_spinner);
        themeSpinner = findViewById(R.id.theme_spinner);
        showPasswordButton = findViewById(R.id.show_password_button);
        Button saveButton = findViewById(R.id.save_button);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.folder_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        folderSpinner.setAdapter(adapter);

        ArrayAdapter<CharSequence> themeAdapter = ArrayAdapter.createFromResource(this,
                R.array.theme_options, android.R.layout.simple_spinner_item);
        themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        themeSpinner.setAdapter(themeAdapter);

        loadSettings();

        showPasswordButton.setOnClickListener(v -> togglePasswordVisibility());
        saveButton.setOnClickListener(v -> saveSettings());
    }

    private void loadSettings() {
        usernameEdit.setText(credentialsManager.getUsername());
        passwordEdit.setText(credentialsManager.getPassword());
        portEdit.setText(String.valueOf(credentialsManager.getPort()));
        autoStartCheckbox.setChecked(credentialsManager.isAutoStart());
        saveTokenCheckbox.setChecked(credentialsManager.isSaveToken());
        folderSpinner.setSelection(credentialsManager.getFolderType());
        themeSpinner.setSelection(credentialsManager.getTheme());
    }

    private void togglePasswordVisibility() {
        passwordVisible = !passwordVisible;
        if (passwordVisible) {
            passwordEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            showPasswordButton.setText(R.string.hide);
        } else {
            passwordEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            showPasswordButton.setText(R.string.show);
        }
        passwordEdit.setSelection(passwordEdit.getText().length());
    }

    private void saveSettings() {
        String username = usernameEdit.getText().toString().trim();
        String password = passwordEdit.getText().toString().trim();
        String portStr = portEdit.getText().toString().trim();

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, R.string.credentials_empty_error, Toast.LENGTH_SHORT).show();
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port < 1024 || port > 65535) {
                Toast.makeText(this, R.string.port_range_error, Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, R.string.port_invalid_error, Toast.LENGTH_SHORT).show();
            return;
        }

        credentialsManager.setCredentials(username, password);
        credentialsManager.setPort(port);
        credentialsManager.setAutoStart(autoStartCheckbox.isChecked());
        credentialsManager.setSaveToken(saveTokenCheckbox.isChecked());
        credentialsManager.setFolderType(folderSpinner.getSelectedItemPosition());
        credentialsManager.setTheme(themeSpinner.getSelectedItemPosition());

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
