package com.lightchat.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.content.Intent;

import com.lightchat.R;
import com.lightchat.SessionStore;

public class ConversationsActivity extends Activity {
    private SessionStore session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = new SessionStore(this);
        if (!session.hasSession()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_conversations);

        FrameLayout content = findViewById(R.id.content);
        content.addView(placeholder("Aucune discussion pour l'instant."));

        setNavSelection(findViewById(R.id.tab_chats));
        bindNav(findViewById(R.id.tab_chats), "Aucune discussion pour l'instant.");
        bindNav(findViewById(R.id.tab_friends), "Tes amis apparaîtront ici.");
        bindNav(findViewById(R.id.tab_search), "Recherche d'amis par pseudo.");
        bindNav(findViewById(R.id.tab_settings), "Paramètres (disponible en Phase 6).");
    }

    private TextView placeholder(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getResources().getColor(R.color.on_surface_variant));
        tv.setPadding(24, 24, 24, 24);
        return tv;
    }

    private void bindNav(final TextView tab, final String text) {
        tab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FrameLayout content = findViewById(R.id.content);
                content.removeAllViews();
                content.addView(placeholder(text));
                setNavSelection(tab);
            }
        });
    }

    private void setNavSelection(TextView active) {
        int onVariant = getResources().getColor(R.color.on_surface_variant);
        int primary = getResources().getColor(R.color.primary);
        for (int id : new int[]{R.id.tab_chats, R.id.tab_friends, R.id.tab_search, R.id.tab_settings}) {
            TextView t = findViewById(id);
            boolean isActive = t == active;
            t.setTextColor(isActive ? primary : onVariant);
            t.setTextSize(12);
            t.setTypeface(t.getTypeface(), isActive ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            t.setBackgroundResource(isActive ? R.drawable.bg_input : 0);
        }
    }
}