package com.brouken.player.online;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.brouken.player.R;

import java.util.List;

public final class ListPicker {

    private ListPicker() {
    }

    public interface Row {
        @NonNull
        String title();

        @Nullable
        String detail();

        // the row in use now, drawn in the accent colour
        default boolean current() {
            return false;
        }
    }

    public interface OnPicked {
        void onPicked(int index);
    }

    public static AlertDialog show(final Activity activity, final CharSequence title,
                            final List<? extends Row> rows, final OnPicked onPicked) {
        return show(activity, title, rows, onPicked, 0, null);
    }

    public static AlertDialog show(final Activity activity, final CharSequence title,
                            final List<? extends Row> rows, final OnPicked onPicked,
                            final int actionLabel, @Nullable final Runnable action) {
        final RecyclerView list = new RecyclerView(activity);
        // full height, so the buttons sit at the bottom of the panel
        list.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.addItemDecoration(new Separator(activity));

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(list)
                .setNegativeButton(android.R.string.cancel, null);
        if (action != null && actionLabel != 0) {
            builder.setNeutralButton(actionLabel, (dialog, which) -> action.run());
        }
        final AlertDialog dialog = builder.create();

        list.setAdapter(new Adapter(rows, index -> {
            dialog.dismiss();
            onPicked.onPicked(index);
        }));

        dressPanel(activity, dialog);
        dialog.show();
        // after show(): a floating dialog clamps a size set before it is shown
        sizePanel(activity, dialog);

        // an unfocused list ignores the remote; the rows do not exist yet, see Panels
        com.brouken.player.Panels.focusFirstRow(list);

        return dialog;
    }

    // a full-height side panel, so the video stays visible
    private static void dressPanel(final Activity activity, final AlertDialog dialog) {
        final android.view.Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        // must be set before show() or the entrance is not animated
        window.setWindowAnimations(R.style.PanelAnimation);
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                androidx.core.content.ContextCompat.getColor(activity, R.color.ui_panel_background)));
        window.setDimAmount(0.2f);
    }

    private static void sizePanel(final Activity activity, final AlertDialog dialog) {
        final android.view.Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.setLayout(com.brouken.player.Panels.width(activity),
                android.view.WindowManager.LayoutParams.MATCH_PARENT);
        window.setGravity(android.view.Gravity.END | android.view.Gravity.TOP);
        // drop the decor view's default inset so the panel sits flush
        window.getDecorView().setPadding(0, 0, 0, 0);
    }

    private static final class Separator extends RecyclerView.ItemDecoration {

        private final Paint paint = new Paint();
        private final float inset;

        Separator(final Activity activity) {
            paint.setColor(0x22FFFFFF);
            paint.setStrokeWidth(activity.getResources().getDisplayMetrics().density);
            inset = 20 * activity.getResources().getDisplayMetrics().density;
        }

        @Override
        public void onDrawOver(@NonNull Canvas canvas, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
            for (int i = 0; i < parent.getChildCount() - 1; i++) {
                final View child = parent.getChildAt(i);
                final float y = child.getBottom();
                canvas.drawLine(inset, y, parent.getWidth() - inset, y, paint);
            }
        }
    }

    private static final class Adapter extends RecyclerView.Adapter<Holder> {

        private final List<? extends Row> rows;
        private final OnPicked onPicked;

        Adapter(List<? extends Row> rows, OnPicked onPicked) {
            this.rows = rows;
            this.onPicked = onPicked;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.online_result_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            final Row row = rows.get(position);
            holder.title.setText(row.title());

            if (row.current()) {
                final int accent = com.brouken.player.Accent.color(holder.title.getContext());
                holder.title.setTextColor(accent);
                holder.detail.setTextColor(accent);
            } else {
                holder.title.setTextColor(holder.defaultTitleColor);
                holder.detail.setTextColor(holder.defaultDetailColor);
            }

            final String detail = row.detail();
            holder.detail.setText(detail == null ? "" : detail);
            holder.detail.setVisibility(detail == null || detail.isEmpty()
                    ? View.GONE : View.VISIBLE);

            holder.itemView.setOnClickListener(v ->
                    onPicked.onPicked(holder.getBindingAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView detail;
        // theme colours, restored when a recycled accent row is rebound
        final int defaultTitleColor;
        final int defaultDetailColor;

        Holder(View view) {
            super(view);
            title = view.findViewById(R.id.row_title);
            detail = view.findViewById(R.id.row_detail);
            defaultTitleColor = title.getCurrentTextColor();
            defaultDetailColor = detail.getCurrentTextColor();
        }
    }
}
