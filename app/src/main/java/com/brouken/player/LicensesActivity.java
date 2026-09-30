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

/**
 * The licences the application ships under, in full.
 *
 * <p>The full text, not a list of names. A list of names is what an earlier
 * version had and it satisfies nobody: MIT, ISC and Apache each require the
 * notice itself to accompany the binary, and the whole reason for putting them
 * in the APK is that an APK handed around outside the releases page carries no
 * attachment with it.
 *
 * <p>The texts are assets gathered at build time from {@code licenses/} at the
 * root of the repository, so this screen and the zip attached to a release are
 * always the same words.
 */
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

        /*
         * Back steps out of a licence to the list before leaving the screen.
         *
         * Through the dispatcher rather than onBackPressed, which this
         * application never sees: it sets enableOnBackInvokedCallback, and with
         * that on, Android 13 and later route Back past the old override
         * entirely -- so a licence was closed and the screen with it in one
         * press, losing the reader's place in the list.
         */
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

    /** Back steps out of a licence to the list before leaving the screen. */
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
