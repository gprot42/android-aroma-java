package com.example.aroma;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
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
    private boolean isInitializing = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyTheme();
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

        setupSpinners();
        loadSettings();
        applyThemeColors();
        
        isInitializing = false;

        themeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!isInitializing) {
                    credentialsManager.setTheme(position);
                    applyThemeColors();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        showPasswordButton.setOnClickListener(v -> togglePasswordVisibility());
        saveButton.setOnClickListener(v -> saveSettings());
    }

    private void setupSpinners() {
        boolean isDark = credentialsManager.getTheme() == CredentialsManager.THEME_DARK;
        int textColor = isDark ? 0xFFffffff : 0xFF111111;
        int spinnerBg = isDark ? 0xFF16213e : 0xFFffffff;

        ArrayAdapter<CharSequence> folderAdapter = new ArrayAdapter<CharSequence>(this,
                android.R.layout.simple_spinner_item,
                getResources().getTextArray(R.array.folder_options)) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                ((TextView) view).setTextColor(getCurrentTextColor());
                return view;
            }
            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                view.setBackgroundColor(getCurrentSpinnerBg());
                ((TextView) view).setTextColor(getCurrentTextColor());
                ((TextView) view).setPadding(24, 24, 24, 24);
                return view;
            }
        };
        folderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        folderSpinner.setAdapter(folderAdapter);
        folderSpinner.setBackgroundColor(spinnerBg);

        ArrayAdapter<CharSequence> themeAdapter = new ArrayAdapter<CharSequence>(this,
                android.R.layout.simple_spinner_item,
                getResources().getTextArray(R.array.theme_options)) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                ((TextView) view).setTextColor(getCurrentTextColor());
                return view;
            }
            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                view.setBackgroundColor(getCurrentSpinnerBg());
                ((TextView) view).setTextColor(getCurrentTextColor());
                ((TextView) view).setPadding(24, 24, 24, 24);
                return view;
            }
        };
        themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        themeSpinner.setAdapter(themeAdapter);
        themeSpinner.setBackgroundColor(spinnerBg);
    }

    private int getCurrentTextColor() {
        boolean isDark = credentialsManager.getTheme() == CredentialsManager.THEME_DARK;
        return isDark ? 0xFFffffff : 0xFF111111;
    }

    private int getCurrentSpinnerBg() {
        boolean isDark = credentialsManager.getTheme() == CredentialsManager.THEME_DARK;
        return isDark ? 0xFF16213e : 0xFFffffff;
    }

    private void applyThemeColors() {
        boolean isDark = credentialsManager.getTheme() == CredentialsManager.THEME_DARK;

        int bgColor = isDark ? 0xFF1a1a2e : 0xFFf5f5f5;
        int panelColor = isDark ? 0xFF16213e : 0xFFffffff;
        int textColor = isDark ? 0xFFffffff : 0xFF111111;
        int textSecondary = isDark ? 0xFFcccccc : 0xFF333333;
        int textMuted = isDark ? 0xFF888888 : 0xFF666666;
        int inputBg = isDark ? 0xFF0d0d1a : 0xFFffffff;

        ScrollView scrollView = findViewById(R.id.settings_scroll);
        scrollView.setBackgroundColor(bgColor);

        LinearLayout container = findViewById(R.id.settings_container);
        container.setBackgroundColor(bgColor);

        TextView title = findViewById(R.id.settings_title);
        title.setTextColor(textColor);

        TextView appearanceLabel = findViewById(R.id.appearance_label);
        appearanceLabel.setTextColor(0xFF4da6ff);

        TextView credentialsLabel = findViewById(R.id.credentials_label);
        credentialsLabel.setTextColor(0xFF4da6ff);

        TextView serverLabel = findViewById(R.id.server_label);
        serverLabel.setTextColor(0xFF4da6ff);

        TextView folderLabel = findViewById(R.id.folder_label);
        folderLabel.setTextColor(0xFF4da6ff);

        TextView aboutLabel = findViewById(R.id.about_label);
        aboutLabel.setTextColor(0xFF4da6ff);

        usernameEdit.setTextColor(textColor);
        usernameEdit.setHintTextColor(textMuted);
        usernameEdit.setBackgroundColor(inputBg);

        passwordEdit.setTextColor(textColor);
        passwordEdit.setHintTextColor(textMuted);
        passwordEdit.setBackgroundColor(inputBg);

        portEdit.setTextColor(textColor);
        portEdit.setBackgroundColor(inputBg);

        TextView portLabel = findViewById(R.id.port_label_text);
        portLabel.setTextColor(textSecondary);

        autoStartCheckbox.setTextColor(textSecondary);
        saveTokenCheckbox.setTextColor(textSecondary);

        TextView credentialsNote = findViewById(R.id.credentials_note);
        credentialsNote.setTextColor(textMuted);

        TextView versionText = findViewById(R.id.version_text);
        versionText.setTextColor(textSecondary);

        TextView descriptionText = findViewById(R.id.description_text);
        descriptionText.setTextColor(textMuted);

        showPasswordButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(panelColor));

        themeSpinner.setBackgroundColor(panelColor);
        folderSpinner.setBackgroundColor(panelColor);
        
        View themeView = themeSpinner.getSelectedView();
        if (themeView instanceof TextView) {
            ((TextView) themeView).setTextColor(textColor);
        }
        View folderView = folderSpinner.getSelectedView();
        if (folderView instanceof TextView) {
            ((TextView) folderView).setTextColor(textColor);
        }
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
        int selectedTheme = themeSpinner.getSelectedItemPosition();
        Log.d("AROMA", "Saving theme: " + selectedTheme + " (0=dark, 1=light)");
        credentialsManager.setTheme(selectedTheme);

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void applyTheme() {
        CredentialsManager cm = new CredentialsManager(this);
        int theme = cm.getTheme();
        if (theme == CredentialsManager.THEME_LIGHT) {
            setTheme(R.style.Theme_Aroma_Light);
        } else {
            setTheme(R.style.Theme_Aroma_Dark);
        }
    }
}
