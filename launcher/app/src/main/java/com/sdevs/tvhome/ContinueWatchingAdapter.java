package com.sdevs.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.BitmapDrawable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ContinueWatchingAdapter extends RecyclerView.Adapter<ContinueWatchingAdapter.Holder> {

    interface CwListener {
        void onCwTileFocused(int position, View card, NuvioContinueWatching.Item item);
        boolean onCwTileKey(int position, int keyCode, KeyEvent event);
    }

    private final Context ctx;
    private final CwListener listener;
    private final boolean animations;
    private final boolean advancedEffects;
    private final boolean reducedMotion;
    private final List<NuvioContinueWatching.Item> items = new ArrayList<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    ContinueWatchingAdapter(Context ctx, CwListener listener, Config cfg) {
        this.ctx = ctx;
        this.listener = listener;
        this.animations = cfg.animations;
        this.advancedEffects = cfg.animations && cfg.advancedEffects;
        this.reducedMotion = cfg.reducedMotion;
        setHasStableIds(true);
    }

    void setItems(List<NuvioContinueWatching.Item> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    NuvioContinueWatching.Item getItem(int position) {
        return (position >= 0 && position < items.size()) ? items.get(position) : null;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).id != 0 ? items.get(position).id : items.get(position).title.hashCode();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_continue_watching, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        NuvioContinueWatching.Item it = items.get(position);

        h.title.setText(it.title);

        String sub = it.episodeDetail;
        if (sub == null || sub.trim().isEmpty()) {
            sub = "Movie";
        }
        h.sub.setText(sub);

        if (it.timeRemaining != null && !it.timeRemaining.isEmpty()) {
            h.badgeText.setText("RESUME \u00B7 " + it.timeRemaining);
        } else {
            h.badgeText.setText("RESUME");
        }

        // Progress bar
        float density = ctx.getResources().getDisplayMetrics().density;
        int cardWidthPx = Math.round(200f * density);
        int progWidth = Math.max(1, Math.round(cardWidthPx * Math.max(0.05f, Math.min(1f, it.progress))));
        ViewGroup.LayoutParams progLp = h.progressBar.getLayoutParams();
        progLp.width = progWidth;
        h.progressBar.setLayoutParams(progLp);

        // Poster image
        if (it.posterBitmap != null) {
            h.banner.setImageBitmap(it.posterBitmap);
            h.banner.setVisibility(View.VISIBLE);
            h.placeholder.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.INVISIBLE);
            h.placeholder.setVisibility(View.VISIBLE);

            if (it.posterUri != null && !it.posterUri.isEmpty()) {
                final int bindPos = position;
                executor.execute(() -> {
                    Bitmap bmp = NuvioContinueWatching.loadPoster(ctx, it.posterUri);
                    if (bmp != null) {
                        it.posterBitmap = bmp;
                        it.ambientColor = AppAdapter.vividColor(bmp);
                        if (ctx instanceof Activity) {
                            ((Activity) ctx).runOnUiThread(() -> {
                                if (bindPos < items.size() && items.get(bindPos) == it) {
                                    notifyItemChanged(bindPos);
                                    if (h.card.isFocused() && listener != null) {
                                        listener.onCwTileFocused(bindPos, h.card, it);
                                    }
                                }
                            });
                        }
                    }
                });
            }
        }

        boolean focused = h.card.isFocused();
        if (h.cwHalo != null) {
            h.cwHalo.setVisibility(focused ? View.VISIBLE : View.GONE);
        }
        float scale = focused && animations ? (advancedEffects ? 1.09f : 1.07f) : 1f;
        h.card.setScaleX(scale);
        h.card.setScaleY(scale);
        h.card.setTranslationY(focused ? -3f * density : 0f);
        h.card.setTranslationZ(focused ? 12f * density : 0f);

        h.card.setOnClickListener(v -> launch(it));
    }

    private void launch(NuvioContinueWatching.Item it) {
        if (it.intentUri != null && !it.intentUri.isEmpty()) {
            try {
                Intent intent = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
                return;
            } catch (Exception ignored) {}
        }
        Intent i = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(it.packageName);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } else {
            Toast.makeText(ctx, it.title, Toast.LENGTH_SHORT).show();
        }
    }

    final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final View placeholder;
        final TextView title;
        final TextView sub;
        final TextView badgeText;
        final View progressTrack;
        final View progressBar;
        final View cwHalo;

        Holder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.card);
            banner = itemView.findViewById(R.id.cwBanner);
            placeholder = itemView.findViewById(R.id.cwPlaceholder);
            title = itemView.findViewById(R.id.cwTitle);
            sub = itemView.findViewById(R.id.cwSub);
            badgeText = itemView.findViewById(R.id.cwBadgeText);
            progressTrack = itemView.findViewById(R.id.cwProgressTrack);
            progressBar = itemView.findViewById(R.id.cwProgressBar);
            cwHalo = itemView.findViewById(R.id.cwHalo);

            final float density = ctx.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 14f * density);
                }
            });
            card.setClipToOutline(true);

            card.setOnFocusChangeListener((view, focused) -> {
                view.animate().cancel();
                if (cwHalo != null) {
                    cwHalo.setVisibility(focused ? View.VISIBLE : View.GONE);
                }
                float scale = focused && animations ? (advancedEffects ? 1.09f : 1.07f) : 1f;
                float tz = focused ? 12f * density : 0f;
                float ty = focused ? -3f * density : 0f;
                if (!animations) {
                    view.setScaleX(scale);
                    view.setScaleY(scale);
                    view.setTranslationZ(tz);
                    view.setTranslationY(ty);
                } else {
                    int dur = reducedMotion ? 90 : 160;
                    view.animate()
                            .scaleX(scale)
                            .scaleY(scale)
                            .translationZ(tz)
                            .translationY(ty)
                            .setDuration(dur)
                            .start();
                }

                if (focused && listener != null) {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION && pos < items.size()) {
                        listener.onCwTileFocused(pos, view, items.get(pos));
                    }
                }
            });

            card.setOnKeyListener((view, keyCode, event) -> {
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return false;
                if (listener != null) {
                    return listener.onCwTileKey(pos, keyCode, event);
                }
                return false;
            });
        }
    }
}
