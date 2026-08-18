package com.lightchat.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import com.lightchat.R;
import com.lightchat.SessionStore;
import com.lightchat.net.ApiClient;
import com.lightchat.util.Async;
import com.lightchat.util.Json;

import java.util.Map;

public class LoginActivity extends Activity {
    private SessionStore session;
    private EditText eUser, ePass, eFirst, eLast, eAge;
    private Spinner genderSpinner;
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
        genderSpinner = findViewById(R.id.input_gender);
        ArrayAdapter<CharSequence> ga = new ArrayAdapter<CharSequence>(this,
                android.R.layout.simple_spinner_item, getResources().getStringArray(R.array.gender_labels));
        ga.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        genderSpinner.setAdapter(ga);
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
        getWindow().getDecorView().startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in));
    }

    private void toggleMode() {
        findViewById(R.id.group_register).setVisibility(registerMode ? View.VISIBLE : View.GONE);
        TextView t = findViewById(R.id.txt_toggle_mode);
        t.setText(registerMode ? R.string.switch_to_login : R.string.switch_to_register);
        Button b = findViewById(R.id.btn_login);
        b.setText(registerMode ? R.string.register_submit : R.string.login_submit);
        status.setText("");
    }

    private void attempt() {
        String userRaw = eUser.getText().toString().trim().toLowerCase();
        String pass = ePass.getText().toString();
        if (userRaw.isEmpty() || pass.isEmpty()) {
            status.setText(R.string.status_missing);
            return;
        }
        String body;
        String path;
        if (registerMode) {
            String first = eFirst.getText().toString().trim();
            String last = eLast.getText().toString().trim();
            String ageStr = eAge.getText().toString().trim();
            if (first.isEmpty() || last.isEmpty() || ageStr.isEmpty()) {
                status.setText(R.string.status_missing_register);
                return;
            }
            if (pass.length() < 6) {
                status.setText(R.string.status_pass_short);
                return;
            }
            if (!userRaw.matches("^[a-z0-9._]{3,20}$")) {
                status.setText(R.string.status_user_invalid);
                return;
            }
            int age;
            try { age = Integer.parseInt(ageStr); } catch (NumberFormatException e) { status.setText(R.string.status_invalid_age); return; }
            if (age < 13 || age > 120) { status.setText(R.string.status_invalid_age); return; }
            String gender = genderValue();
            status.setText(R.string.status_register);
            body = "{\"username\":\"" + esc(userRaw) + "\","
                 + "\"password\":\"" + esc(pass) + "\","
                 + "\"first_name\":\"" + esc(first) + "\","
                 + "\"last_name\":\"" + esc(last) + "\","
                 + "\"age\":" + age + ","
                 + "\"gender\":\"" + gender + "\"}";
            path = "/api/auth/register";
        } else {
            status.setText(R.string.status_login);
            body = "{\"username\":\"" + esc(userRaw) + "\",\"password\":\"" + esc(pass) + "\"}";
            path = "/api/auth/login";
        }
        callApi(path, body);
    }

    private String genderValue() {
        String[] values = getResources().getStringArray(R.array.gender_values);
        int pos = genderSpinner.getSelectedItemPosition();
        if (pos >= 0 && pos < values.length) return values[pos];
        return "other";
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
                    status.setText(errorMessage(resp.body));
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

    private String errorMessage(String respBody) {
        try {
            Map<String, Object> m = Json.parseObject(respBody);
            Object e = m.get("error");
            if (e instanceof String && !((String) e).isEmpty()) return (String) e;
        } catch (Exception ignored) {
        }
        return "Échec de la requête.";
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void goConversations() {
        startActivity(new Intent(this, ConversationsActivity.class));
        finish();
    }
}