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

        default boolean current() {
            return false;
        }

        default int icon() {
            return 0;
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
        list.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.addItemDecoration(new Separator(activity));

        final AlertDialog.Builder builder = new AlertDialog.Builder(com.brouken.player.AppTheme.panels(activity))
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
        // the title and buttons exist only once shown
        final com.brouken.player.PlayerStyle style = com.brouken.player.PlayerStyle.of(activity);
        if (style != null) {
            style.dressDialogText(dialog);
        }

        // an unfocused list ignores the remote; start on the row in use so OK changes nothing
        int current = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).current()) {
                current = i;
                break;
            }
        }
        com.brouken.player.Panels.focusRow(list, current);

        return dialog;
    }

    private static int highlight(final android.content.Context context) {
        final int accent = com.brouken.player.Accent.color(context);
        final com.brouken.player.PlayerStyle style = com.brouken.player.PlayerStyle.of(context);
        return style != null ? style.highlight(accent) : accent;
    }

    private static void dressPanel(final Activity activity, final AlertDialog dialog) {
        final android.view.Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        // must be set before show() or the entrance is not animated
        window.setWindowAnimations(R.style.PanelAnimation);
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                androidx.core.content.ContextCompat.getColor(activity, R.color.ui_panel_background)));
        final com.brouken.player.PlayerStyle style = com.brouken.player.PlayerStyle.of(activity);
        if (style != null) {
            window.setBackgroundDrawable(style.panelBackground());
        }
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
            final com.brouken.player.PlayerStyle style = com.brouken.player.PlayerStyle.of(activity);
            paint.setColor(style != null ? style.dividerColour() : 0x22FFFFFF);
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
        private final boolean iconColumn;

        Adapter(List<? extends Row> rows, OnPicked onPicked) {
            this.rows = rows;
            this.onPicked = onPicked;
            boolean any = false;
            for (final Row row : rows) {
                if (row.icon() != 0 || row.current()) {
                    any = true;
                    break;
                }
            }
            iconColumn = any;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.online_result_item, parent, false);
            final com.brouken.player.PlayerStyle style = com.brouken.player.PlayerStyle.of(parent.getContext());
            if (style != null) {
                style.dressListRow(view);
            }
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            final Row row = rows.get(position);
            holder.title.setText(row.title());

            final int accent = highlight(holder.title.getContext());
            if (row.current()) {
                holder.title.setTextColor(accent);
                holder.detail.setTextColor(accent);
            } else {
                holder.title.setTextColor(holder.defaultTitleColor);
                holder.detail.setTextColor(holder.defaultDetailColor);
            }

            holder.icon.setVisibility(iconColumn ? View.VISIBLE : View.GONE);
            if (row.icon() != 0) {
                holder.icon.setImageResource(row.icon());
                final com.brouken.player.PlayerStyle style =
                        com.brouken.player.PlayerStyle.of(holder.icon.getContext());
                holder.icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                        style != null ? style.iconColour() : 0xD9FFFFFF));
            } else if (row.current()) {
                holder.icon.setImageResource(R.drawable.ic_check_24dp);
                holder.icon.setImageTintList(android.content.res.ColorStateList.valueOf(accent));
            } else {
                holder.icon.setImageDrawable(null);
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
        final android.widget.ImageView icon;
        // theme colours, restored when a recycled accent row is rebound
        final int defaultTitleColor;
        final int defaultDetailColor;

        Holder(View view) {
            super(view);
            title = view.findViewById(R.id.row_title);
            detail = view.findViewById(R.id.row_detail);
            icon = view.findViewById(R.id.row_icon);
            defaultTitleColor = title.getCurrentTextColor();
            defaultDetailColor = detail.getCurrentTextColor();
        }
    }
}
