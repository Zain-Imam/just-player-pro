package com.brouken.player;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Putting the buttons at the bottom right in the order somebody wants them.
 *
 * <p>Top of this list is the leftmost button in the player. Moved with arrows
 * rather than by dragging: a television has no finger to drag with, and an
 * arrow is the one gesture that works identically with a thumb and a D-pad.
 *
 * <p>The three conditional buttons are listed whatever this device does with
 * them, dimmed and with a line saying why, so their place can be chosen before
 * they ever appear. Only the gear cannot be hidden -- it is the way back here.
 */
public class ControlOrderActivity extends AppCompatActivity {

    private final List<String> order = new ArrayList<>();
    private final Set<String> hidden = new LinkedHashSet<>();

    private LinearLayout rows;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Accent.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control_order);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.pref_controls_order);
        }

        rows = findViewById(R.id.control_order_rows);

        order.addAll(ControlOrder.order(this));
        hidden.addAll(ControlOrder.hidden(this));

        final Button reset = findViewById(R.id.control_order_reset);
        reset.setOnClickListener(v -> {
            ControlOrder.reset(this);
            order.clear();
            order.addAll(ControlOrder.order(this));
            hidden.clear();
            draw(0);
        });

        draw(-1);
    }

    /**
     * Rebuild the list, then put the focus back where it was.
     *
     * <p>Rebuilding throws away the view that was focused, and without this a
     * remote was dropped back at the top of the screen after every press --
     * making a button impossible to walk down more than one place at a time.
     */
    private void draw(final int focusRow) {
        draw(focusRow, true);
    }

    private void draw(final int focusRow, final boolean focusUp) {
        rows.removeAllViews();
        final LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < order.size(); i++) {
            final String key = order.get(i);
            final View row = inflater.inflate(R.layout.item_control_order, rows, false);
            final TextView name = row.findViewById(R.id.row_name);
            final TextView note = row.findViewById(R.id.row_note);
            final ImageButton up = row.findViewById(R.id.row_up);
            final ImageButton down = row.findViewById(R.id.row_down);
            final CheckBox shown = row.findViewById(R.id.row_shown);

            name.setText(ControlOrder.label(key));

            final Integer why = ControlOrder.unavailableReason(this, key);
            if (why != null) {
                note.setText(why);
                note.setVisibility(View.VISIBLE);
                // Dimmed, but still orderable: the place can be set in advance.
                name.setAlpha(0.5f);
                note.setAlpha(0.5f);
            }

            final int index = i;
            up.setEnabled(i > 0);
            up.setAlpha(i > 0 ? 1f : 0.3f);
            up.setOnClickListener(v -> move(index, index - 1));

            down.setEnabled(i < order.size() - 1);
            down.setAlpha(i < order.size() - 1 ? 1f : 0.3f);
            down.setOnClickListener(v -> move(index, index + 1));

            shown.setChecked(!hidden.contains(key));
            if (!ControlOrder.canHide(key)) {
                shown.setEnabled(false);
                shown.setAlpha(0.3f);
            } else if (why != null) {
                // Nothing to show or hide while the button is not there at all.
                shown.setEnabled(false);
                shown.setAlpha(0.3f);
            } else {
                shown.setOnCheckedChangeListener((view, checked) -> {
                    if (checked) {
                        hidden.remove(key);
                    } else {
                        hidden.add(key);
                    }
                    save();
                });
            }

            rows.addView(row);
        }

        /*
         * The focus follows the button, onto the arrow that moved it.
         *
         * Landing on the opposite arrow would mean a second press sent it
         * straight back where it came from, so with a remote a button could
         * never be moved more than one place: press, press, and it is where it
         * started. The arrow that was used is the one that keeps the focus, and
         * only where it has run out -- the ends of the list -- does the other
         * one take it.
         */
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
        final String key = order.remove(from);
        order.add(to, key);
        save();
        draw(to, to < from);
    }

    private void save() {
        ControlOrder.save(this, order, hidden);
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
