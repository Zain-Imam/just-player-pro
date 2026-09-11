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

        dialog.show();

        // A television focuses nothing until something asks; an unfocused list
        // ignores the remote entirely.
        list.post(() -> {
            final RecyclerView.ViewHolder first = list.findViewHolderForAdapterPosition(0);
            if (first != null) {
                first.itemView.requestFocus();
            }
        });

        return dialog;
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

        Holder(View view) {
            super(view);
            title = view.findViewById(R.id.row_title);
            detail = view.findViewById(R.id.row_detail);
        }
    }
}
