package com.lightchat.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.lightchat.R;
import com.lightchat.SessionStore;
import com.lightchat.net.ApiClient;
import com.lightchat.util.Async;
import com.lightchat.util.Json;

import java.util.Map;

public class LoginActivity extends Activity {
    private SessionStore session;
    private EditText eUser, ePass, eFirst, eLast, eAge, eGender;
    private TextView status;
    private boolean registerMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = new SessionStore(this);
        if (session.hasSession()) {
            goConversations();
            return;
        }
        setContentView(R.layout.activity_login);

        eUser = findViewById(R.id.input_username);
        ePass = findViewById(R.id.input_password);
        eFirst = findViewById(R.id.input_first_name);
        eLast = findViewById(R.id.input_last_name);
        eAge = findViewById(R.id.input_age);
        eGender = findViewById(R.id.input_gender);
        status = findViewById(R.id.txt_status);
        Button login = findViewById(R.id.btn_login);
        TextView toggle = findViewById(R.id.txt_toggle_mode);

        login.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { attempt(); }
        });
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { registerMode = !registerMode; toggleMode(); }
        });
        toggleMode();
    }

    private void toggleMode() {
        findViewById(R.id.group_register).setVisibility(registerMode ? View.VISIBLE : View.GONE);
        TextView t = findViewById(R.id.txt_toggle_mode);
        t.setText(registerMode ? R.string.switch_to_login : R.string.switch_to_register);
        status.setText("");
    }

    private void attempt() {
        String user = eUser.getText().toString().trim();
        String pass = ePass.getText().toString();
        if (user.isEmpty() || pass.isEmpty()) {
            status.setText(R.string.status_missing);
            return;
        }
        String body;
        String path;
        if (registerMode) {
            String first = eFirst.getText().toString().trim();
            String last = eLast.getText().toString().trim();
            String ageStr = eAge.getText().toString().trim();
            String gender = eGender.getText().toString().trim();
            if (first.isEmpty() || last.isEmpty() || ageStr.isEmpty() || gender.isEmpty()) {
                status.setText(R.string.status_missing_register);
                return;
            }
            int age;
            try { age = Integer.parseInt(ageStr); } catch (NumberFormatException e) { status.setText(R.string.status_invalid_age); return; }
            if (age < 1 || age > 130) { status.setText(R.string.status_invalid_age); return; }
            status.setText(R.string.status_register);
            body = "{\"username\":\"" + esc(user) + "\","
                 + "\"password\":\"" + esc(pass) + "\","
                 + "\"first_name\":\"" + esc(first) + "\","
                 + "\"last_name\":\"" + esc(last) + "\","
                 + "\"age\":" + age + ","
                 + "\"gender\":\"" + esc(gender) + "\"}";
            path = "/api/auth/register";
        } else {
            status.setText(R.string.status_login);
            body = "{\"username\":\"" + esc(user) + "\",\"password\":\"" + esc(pass) + "\"}";
            path = "/api/auth/login";
        }
        callApi(path, body);
    }

    private void callApi(String path, String body) {
        Async.exec(this, new Async.Worker<ApiClient.ApiResponse>() {
            @Override public ApiClient.ApiResponse run() throws Exception {
                return ApiClient.call("POST", path, body, null);
            }
        }, new Async.UI<ApiClient.ApiResponse>() {
            @Override public void on(ApiClient.ApiResponse resp, Exception err) {
                if (err != null) {
                    status.setText("Erreur réseau : " + err.getMessage());
                    return;
                }
                if (resp.status != 200) {
                    status.setText("Échec (" + resp.status + ").");
                    return;
                }
                try {
                    Map<String, Object> map = Json.parseObject(resp.body);
                    String token = (String) map.get("token");
                    Map<String, Object> u = (Map<String, Object>) map.get("user");
                    if (token == null || u == null) {
                        status.setText("Réponse invalide.");
                        return;
                    }
                    session.save(token, (String) u.get("username"), (String) u.get("id"));
                    goConversations();
                } catch (Exception e) {
                    status.setText("Réponse invalide.");
                }
            }
        });
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void goConversations() {
        startActivity(new Intent(this, ConversationsActivity.class));
        finish();
    }
}