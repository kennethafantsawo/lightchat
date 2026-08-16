package com.lightchat.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.content.Intent;

import com.lightchat.R;
import com.lightchat.SessionStore;

public class LoginActivity extends Activity {
    private SessionStore session;
    private EditText username;
    private EditText password;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = new SessionStore(this);
        if (session.hasSession()) {
            goConversations();
            return;
        }
        setContentView(R.layout.activity_login);

        username = findViewById(R.id.input_username);
        password = findViewById(R.id.input_password);
        status = findViewById(R.id.txt_status);
        Button login = findViewById(R.id.btn_login);

        login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Phase 3 : appel réel à POST /api/auth/login.
                // Shell actuel : on simule une connexion locale.
                if (username.length() == 0 || password.length() == 0) {
                    status.setText("Renseigne pseudo et mot de passe.");
                    return;
                }
                session.save("dev-token", username.getText().toString().trim(), "dev-id");
                goConversations();
            }
        });
    }

    private void goConversations() {
        startActivity(new Intent(this, ConversationsActivity.class));
        finish();
    }
}