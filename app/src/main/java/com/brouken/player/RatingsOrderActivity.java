package com.brouken.player;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.brouken.player.online.Ratings;

import java.util.ArrayList;
import java.util.List;

// arrows instead of drag, for the D-pad
public class RatingsOrderActivity extends AppCompatActivity {

    private final List<String> order = new ArrayList<>();

    private LinearLayout rows;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Accent.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control_order);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.pref_ratings_order);
        }

        final TextView hint = findViewById(R.id.control_order_hint);
        hint.setText(R.string.ratings_order_hint);
        rows = findViewById(R.id.control_order_rows);

        order.addAll(Ratings.order(this));

        final Button reset = findViewById(R.id.control_order_reset);
        reset.setOnClickListener(v -> {
            Ratings.resetOrder(this);
            order.clear();
            order.addAll(Ratings.order(this));
            draw(0, true);
        });

        draw(-1, true);
    }

    // rebuilding drops the focused view, so focus is restored afterwards
    private void draw(final int focusRow, final boolean focusUp) {
        rows.removeAllViews();
        final LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < order.size(); i++) {
            final String site = order.get(i);
            final View row = inflater.inflate(R.layout.item_control_order, rows, false);
            final TextView name = row.findViewById(R.id.row_name);
            final ImageButton up = row.findViewById(R.id.row_up);
            final ImageButton down = row.findViewById(R.id.row_down);
            final CheckBox shown = row.findViewById(R.id.row_shown);

            name.setText(Ratings.spokenName(this, site));
            // the site's own colours, as on the info card
            final ImageView icon = row.findViewById(R.id.row_icon);
            icon.setImageTintList(null);
            icon.setImageResource(Ratings.icon(site, 100, null));

            final int index = i;
            up.setEnabled(i > 0);
            up.setAlpha(i > 0 ? 1f : 0.3f);
            up.setOnClickListener(v -> move(index, index - 1));

            down.setEnabled(i < order.size() - 1);
            down.setAlpha(i < order.size() - 1 ? 1f : 0.3f);
            down.setOnClickListener(v -> move(index, index + 1));

            shown.setChecked(Ratings.isOn(this, site));
            shown.setOnCheckedChangeListener((view, checked) -> Ratings.setOn(this, site, checked));

            rows.addView(row);
        }

        // focus stays on the arrow just used, so repeated presses keep moving the site
        if (focusRow >= 0 && focusRow < rows.getChildCount()) {
            final View row = rows.getChildAt(focusRow);
            final View wanted = row.findViewById(focusUp ? R.id.row_up : R.id.row_down);
            final View other = row.findViewById(focusUp ? R.id.row_down : R.id.row_up);
            final View target = wanted.isEnabled() ? wanted : other;
            target.post(target::requestFocus);
        }
    }

    private void move(final int from, final int to) {
        if (to < 0 || to >= order.size()) {
            return;
        }
        final String site = order.remove(from);
        order.add(to, site);
        Ratings.saveOrder(this, order);
        draw(to, to < from);
    }

    @Override
    public boolean onOptionsItemSelected(final android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
