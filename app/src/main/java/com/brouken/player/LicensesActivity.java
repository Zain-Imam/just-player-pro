package com.brouken.player;

import android.content.res.AssetManager;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// full licence texts, which MIT, ISC and Apache require to ship with the binary
// gathered at build time from licenses/ at the repository root
public class LicensesActivity extends AppCompatActivity {

    private static final String DIR = "licenses";

    private final List<String> titles = new ArrayList<>();
    private final List<String> files = new ArrayList<>();

    private ListView list;
    private ScrollView textHolder;
    private TextView text;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Accent.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_licenses);

        list = findViewById(R.id.licenses_list);
        textHolder = findViewById(R.id.licenses_text_holder);
        text = findViewById(R.id.licenses_text);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.pref_licenses);
        }

        // uses the dispatcher: enableOnBackInvokedCallback bypasses onBackPressed
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (showingText()) {
                            backToList();
                            return;
                        }
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                    }
                });

        readIndex();
        list.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, titles));
        list.setOnItemClickListener((parent, view, position, id) -> open(position));
    }

    private void readIndex() {
        final AssetManager assets = getAssets();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                assets.open(DIR + "/index.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final int bar = line.indexOf('|');
                if (bar > 0 && bar < line.length() - 1) {
                    titles.add(line.substring(0, bar));
                    files.add(line.substring(bar + 1));
                }
            }
        } catch (IOException e) {
            Utils.log("No licence index: " + e);
        }
    }

    private void open(final int position) {
        if (position < 0 || position >= files.size()) {
            return;
        }
        text.setText(read(files.get(position)));
        textHolder.scrollTo(0, 0);
        list.setVisibility(View.GONE);
        textHolder.setVisibility(View.VISIBLE);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(titles.get(position));
        }
        textHolder.requestFocus();
    }

    private String read(final String name) {
        final StringBuilder out = new StringBuilder();
        try (InputStream in = getAssets().open(DIR + "/" + name);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        } catch (IOException e) {
            return getString(R.string.licenses_unavailable);
        }
        return out.toString();
    }

    private boolean showingText() {
        return textHolder != null && textHolder.getVisibility() == View.VISIBLE;
    }

    private void backToList() {
        textHolder.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        list.requestFocus();
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.pref_licenses);
        }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            if (showingText()) {
                backToList();
            } else {
                finish();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

}
