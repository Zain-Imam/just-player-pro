package com.brouken.player.online;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.brouken.player.R;

import java.util.List;

final class PosterPicker {

    private PosterPicker() {
    }

    interface Item {
        @Nullable
        String posterPath();

        float aspect();

        @NonNull
        String title();

        @Nullable
        String subtitle();
    }

    interface OnPicked {
        void onPicked(int index);
    }

    private static int spanCount(final Activity activity) {
        final Configuration config = activity.getResources().getConfiguration();
        final int smallestDp = config.smallestScreenWidthDp;
        final boolean landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE;

        if (smallestDp >= 600) {
            return landscape ? 5 : 4;
        }
        return landscape ? 4 : 3;
    }

    static AlertDialog show(final Activity activity, final CharSequence title,
                            final List<? extends Item> items, final OnPicked onPicked) {
        return show(activity, title, items, onPicked, null);
    }

    /**
     * @param onBack one step back, or null when this is the first step.
     *               <p>
     *               A series is three questions deep — which programme, which
     *               season, which episode — and answering one of them wrongly
     *               used to mean cancelling out to the film and typing the
     *               title again, because Cancel is the only thing a dialog
     *               offers. The lists are already in hand by then, so going
     *               back a step costs nothing and asks nobody anything.
     */
    static AlertDialog show(final Activity activity, final CharSequence title,
                            final List<? extends Item> items, final OnPicked onPicked,
                            @Nullable final Runnable onBack) {
        final RecyclerView grid = new RecyclerView(activity);
        grid.setLayoutManager(new GridLayoutManager(activity, spanCount(activity)));
        grid.setHasFixedSize(true);
        final int pad = (int) (8 * activity.getResources().getDisplayMetrics().density);
        grid.setPadding(pad, pad, pad, pad);
        grid.setClipToPadding(false);

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(grid)
                .setNegativeButton(android.R.string.cancel, null);
        if (onBack != null) {
            builder.setNeutralButton(R.string.online_back, (d, which) -> onBack.run());
        }
        final AlertDialog dialog = builder.create();

        grid.setAdapter(new Adapter(items, index -> {
            dialog.dismiss();
            onPicked.onPicked(index);
        }));

        dialog.show();

        // On a television nothing is focused until something asks to be, and an
        // unfocused grid ignores the remote entirely. The tiles are not laid
        // out yet at this point, which is why asking once was not enough —
        // see Panels.
        com.brouken.player.Panels.focusFirstRow(grid);

        return dialog;
    }

    private static final class Adapter extends RecyclerView.Adapter<Holder> {

        private final List<? extends Item> items;
        private final OnPicked onPicked;

        Adapter(List<? extends Item> items, OnPicked onPicked) {
            this.items = items;
            this.onPicked = onPicked;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.online_poster_item, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            final Item item = items.get(position);

            holder.title.setText(item.title());
            final String subtitle = item.subtitle();
            holder.subtitle.setText(subtitle == null ? "" : subtitle);
            holder.subtitle.setVisibility(subtitle == null || subtitle.isEmpty()
                    ? View.GONE : View.VISIBLE);

            // Sized from the column the layout manager gave us, in the shape
            // this particular item's image actually is.
            final float aspect = item.aspect();
            holder.poster.post(() -> {
                final int width = holder.poster.getWidth();
                if (width <= 0) {
                    return;
                }
                final int height = Math.round(width / aspect);
                if (holder.poster.getLayoutParams().height != height) {
                    holder.poster.getLayoutParams().height = height;
                    holder.poster.requestLayout();
                }
            });

            Posters.load(holder.poster, Posters.url(item.posterPath()),
                    R.drawable.online_poster_placeholder);

            holder.itemView.setOnClickListener(v -> onPicked.onPicked(holder.getBindingAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView poster;
        final TextView title;
        final TextView subtitle;

        Holder(View view) {
            super(view);
            poster = view.findViewById(R.id.poster);
            title = view.findViewById(R.id.title);
            subtitle = view.findViewById(R.id.subtitle);
        }
    }
}
