package com.brouken.player;

import android.app.Activity;
import android.content.Intent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SettingsSearch {

    private static final class Hit {
        final String title;
        final String page;
        final String pageTitle;
        @Nullable
        final String key;
        final String words;

        Hit(final String title, final String page, final String pageTitle,
            @Nullable final String key, final String words) {
            this.title = title;
            this.page = page;
            this.pageTitle = pageTitle;
            this.key = key;
            this.words = words;
        }
    }

    private SettingsSearch() {
    }

    static void show(final Activity activity) {
        final List<Hit> all = index(activity);
        final float density = activity.getResources().getDisplayMetrics().density;

        final LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        final int pad = Math.round(20 * density);
        box.setPadding(pad, Math.round(8 * density), pad, 0);
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint(R.string.set_search);
        box.addView(input);
        final TextView none = new TextView(activity);
        none.setText(R.string.set_search_none);
        none.setPadding(0, pad, 0, pad);
        none.setVisibility(View.GONE);
        box.addView(none);
        final ListView results = new ListView(activity);
        box.addView(results, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(360 * density)));

        final List<Hit> shown = new ArrayList<>(all);
        final ArrayAdapter<Hit> adapter = new ArrayAdapter<Hit>(activity,
                android.R.layout.simple_list_item_2, android.R.id.text1, shown) {
            @NonNull
            @Override
            public View getView(final int position, @Nullable final View convertView,
                                @NonNull final ViewGroup parent) {
                final View row = super.getView(position, convertView, parent);
                final Hit hit = getItem(position);
                ((TextView) row.findViewById(android.R.id.text1)).setText(hit == null ? "" : hit.title);
                ((TextView) row.findViewById(android.R.id.text2)).setText(hit == null ? "" : hit.pageTitle);
                return row;
            }
        };
        results.setAdapter(adapter);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        results.setOnItemClickListener((parent, view, position, id) -> {
            final Hit hit = shown.get(position);
            dialog.dismiss();
            activity.startActivity(new Intent(activity, SettingsActivity.class)
                    .putExtra(SettingsActivity.EXTRA_PAGE, hit.page)
                    .putExtra(SettingsActivity.EXTRA_HIGHLIGHT, hit.key));
            // a page opened by an earlier search gives way, so Back leads to the categories
            if (activity.getIntent().hasExtra(SettingsActivity.EXTRA_PAGE)) {
                activity.finish();
            }
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(final Editable text) {
                final String query = text.toString().trim().toLowerCase(Locale.getDefault());
                shown.clear();
                for (final Hit hit : all) {
                    if (query.isEmpty() || hit.words.contains(query)) {
                        shown.add(hit);
                    }
                }
                adapter.notifyDataSetChanged();
                none.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
            }
        });
        dialog.show();
        input.requestFocus();
    }

    private static List<Hit> index(final Activity activity) {
        final List<Hit> hits = new ArrayList<>();
        final PreferenceManager manager = new PreferenceManager(activity);
        final PreferenceScreen root = manager.inflateFromResource(activity, R.xml.root_preferences, null);
        // the page hides it on a device without a battery, so the search does too
        final Preference battery = root.findPreference(PlayerCorner.BATTERY);
        if (battery != null && !PlayerCorner.hasBattery(activity)) {
            battery.setVisible(false);
        }
        for (int i = 0; i < root.getPreferenceCount(); i++) {
            final Preference page = root.getPreference(i);
            if (page instanceof PreferenceScreen && page.getKey() != null) {
                final String pageTitle = String.valueOf(page.getTitle());
                collect((PreferenceGroup) page, page.getKey(), pageTitle, pageTitle, hits);
            }
        }
        return hits;
    }

    private static void collect(final PreferenceGroup group, final String page, final String pageTitle,
                                final String section, final List<Hit> hits) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            final Preference preference = group.getPreference(i);
            if (preference instanceof PreferenceGroup) {
                final CharSequence heading = preference.getTitle();
                collect((PreferenceGroup) preference, page, pageTitle,
                        heading == null ? section : section + " " + heading, hits);
                continue;
            }
            final CharSequence title = preference.getTitle();
            if (title == null || title.length() == 0 || !preference.isVisible()) {
                continue;
            }
            final CharSequence summary = preference.getSummary();
            final String words = (title + " " + (summary == null ? "" : summary) + " " + section)
                    .toLowerCase(Locale.getDefault());
            hits.add(new Hit(title.toString(), page, pageTitle, preference.getKey(), words));
        }
    }
}
