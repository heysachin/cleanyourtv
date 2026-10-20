package com.sdevs.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * then the module cards the owner chose to show (by label), the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);
    }

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1, CARD = 2, WALLPAPER = 3, PRAYER = 4, CONTINUE_WATCHING = 5;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        Drawable banner;
        final Drawable icon;
        /** Text under the tile when it is focused in the grid: the label, plus the caption for a card. */
        final String name;
        /** Ambient glow colour; 0 until sampled from the banner, or the card's accent from the start. */
        int ambient;
        String intentUri;
        String posterUrl;
        final String subtitle;
        final float progress;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(APP, label, component, banner, icon, null, 0);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent) {
            this(kind, label, component, banner, icon, caption, accent, null, null, null, 0f);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent, String intentUri, String posterUrl, String subtitle, float progress) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component != null ? component.getPackageName() : "com.nuvio.tv";
            this.banner = banner;
            this.icon = icon;
            this.name = caption == null ? label : label + " \u00B7 " + caption;
            this.ambient = accent;
            this.intentUri = intentUri;
            this.posterUrl = posterUrl;
            this.subtitle = subtitle;
            this.progress = progress;
        }
    }

    static final float FOCUS_SCALE = 1.10f;
    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight, capped at the design size so 4 columns on a 1080p TV look exactly as before.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        int fit = (int) ((cell - 12 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return tileHeightPx + Math.round(45 * density);
    }

    private final Context ctx;
    private final TileListener listener;
    private int dockSize;
    /** Dock order (config.xml dock_apps, or a theme's config.json). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final boolean animations;
    private final boolean advancedEffects;
    private final boolean showHomeSettings;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): no overshoot, shorter fades. */
    private final boolean reducedMotion;
    /** Shows the Wallpaper tile, which opens the picker, when a wallpaper folder is set. */
    private final boolean showWallpaperTile;
    /** Shows the Prayer Times tile when prayer times are on. */
    private final boolean showPrayerTile;
    private final List<Entry> items = new ArrayList<>();

    int getDockSize() {
        return dockSize;
    }

    AppAdapter(Context ctx, TileListener listener, int dockSize, Config cfg) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = cfg.dockApps;
        this.hidden = new HashSet<>(cfg.hiddenApps);
        this.animations = cfg.animations;
        this.advancedEffects = cfg.animations && cfg.advancedEffects;
        this.showHomeSettings = cfg.showHomeSettingsShortcut;
        this.reducedMotion = cfg.reducedMotion;
        this.showWallpaperTile = cfg.wallpaperFolder != null;
        this.showPrayerTile = cfg.prayerTimes;
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /**
     * Rebuilds the tiles. A shown module card stands in for every tile of its package (same component,
     * so focus follows it); hidden_apps wins over a card, and a card's package in dock_apps takes that slot.
     */
    void reload(List<Cards.Card> cards) {
        PackageManager pm = ctx.getPackageManager();
        // Query both leanback and regular launcher categories to find all installed apps.
        Intent probeLeanback = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Intent probeLauncher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Intent probeInfo = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_INFO);
        List<ResolveInfo> foundLeanback = pm.queryIntentActivities(probeLeanback, 0);
        List<ResolveInfo> foundLauncher = pm.queryIntentActivities(probeLauncher, 0);
        List<ResolveInfo> foundInfo = pm.queryIntentActivities(probeInfo, 0);

        // Merge results, preferring leanback entries (which have TV-optimized banners).
        // Use package name as the dedup key.
        Map<String, ResolveInfo> byPkg = new HashMap<>();
        // Add leanback entries first (preferred).
        for (ResolveInfo ri : foundLeanback) {
            if (ri.activityInfo == null) continue;
            byPkg.put(ri.activityInfo.packageName, ri);
        }
        // Add launcher entries only if a leanback entry doesn't already exist.
        for (ResolveInfo ri : foundLauncher) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (!byPkg.containsKey(pkg)) {
                byPkg.put(pkg, ri);
            }
        }
        // Add info entries if not already present.
        for (ResolveInfo ri : foundInfo) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (!byPkg.containsKey(pkg)) {
                byPkg.put(pkg, ri);
            }
        }
        // Also scan all installed applications to catch any OTT / sideloaded / system apps with launch intents
        try {
            List<android.content.pm.ApplicationInfo> allApps = pm.getInstalledApplications(0);
            for (android.content.pm.ApplicationInfo ai : allApps) {
                if (ai == null || ai.packageName == null || ai.packageName.equals(ctx.getPackageName()) || hidden.contains(ai.packageName)) continue;
                if (!byPkg.containsKey(ai.packageName)) {
                    Intent li = pm.getLeanbackLaunchIntentForPackage(ai.packageName);
                    if (li == null) li = pm.getLaunchIntentForPackage(ai.packageName);
                    if (li != null) {
                        ResolveInfo ri = pm.resolveActivity(li, 0);
                        if (ri != null && ri.activityInfo != null) {
                            byPkg.put(ai.packageName, ri);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : byPkg.values()) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(component)) continue;   // the card stands in for this tile
            Drawable banner = loadBanner(pm, ri);
            Drawable icon = ri.loadIcon(pm);
            if (card != null) {
                fresh.add(new Entry(Entry.CARD, card.label, component, banner, icon, card.caption, card.accent));
            } else {
                // Same cleaner as the card prompt: an app label with a bidi override or line separator must not
                // redraw the tile name as something else (#117).
                fresh.add(new Entry(Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg), component, banner, icon));
            }
        }
        List<String> savedOrder = loadSavedOrder(ctx);
        final List<String> effectiveOrder = (savedOrder != null && !savedOrder.isEmpty()) ? savedOrder : favourites;

        Collections.sort(fresh, (a, b) -> {
            int fa = effectiveOrder.indexOf(a.pkg), fb = effectiveOrder.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            // Cards come right after the configured dock apps, before the alphabetical grid.
            boolean ca = a.kind == Entry.CARD, cb = b.kind == Entry.CARD;
            if (ca != cb) return ca ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });

        // Add Wallpaper tile
        if (showWallpaperTile) {
            fresh.add(new Entry(Entry.WALLPAPER, ctx.getString(R.string.wallpaper),
                    new ComponentName(ctx, WallpaperPickerActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_wallpaper), null, 0xFF3B82F6));
        }

        // Retain the optional Default-home tile until dependent tile-ordering work is merged.
        if (showHomeSettings
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            fresh.add(new Entry(Entry.HOME_SETTINGS, ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_home_settings), null, 0));
        }
        // Prayer Times tile near the end of the grid: today's six times, full screen.
        if (showPrayerTile) {
            fresh.add(new Entry(Entry.PRAYER, ctx.getString(R.string.prayer_times_title),
                    new ComponentName(ctx, PrayerTimesActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_prayer_isha), null, 0));
        }

        this.dockSize = Math.min(8, fresh.size());
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    static List<String> loadSavedOrder(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE);
        String s = sp.getString("app_order", null);
        if (s == null || s.trim().isEmpty()) return null;
        String[] parts = s.split(",");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            if (!p.trim().isEmpty()) list.add(p.trim());
        }
        return list;
    }

    static void saveOrder(Context ctx, List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (e.pkg != null && !e.pkg.isEmpty() && e.kind != Entry.WALLPAPER && e.kind != Entry.HOME_SETTINGS && e.kind != Entry.PRAYER) {
                if (sb.length() > 0) sb.append(",");
                sb.append(e.pkg);
            }
        }
        ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("app_order", sb.toString())
                .apply();
    }

    void showAppDialog(int position, Entry e) {
        if (position < 0 || position >= items.size()) return;
        if (e.kind == Entry.WALLPAPER) {
            ctx.startActivity(new Intent(ctx, WallpaperPickerActivity.class));
            return;
        }

        View panel = LayoutInflater.from(ctx).inflate(R.layout.dialog_app_options, null);
        ImageView appIcon = panel.findViewById(R.id.dialogAppIcon);
        TextView appTitle = panel.findViewById(R.id.dialogAppTitle);
        TextView appPkg = panel.findViewById(R.id.dialogAppPkg);
        LinearLayout optionsContainer = panel.findViewById(R.id.dialogOptionsContainer);

        appTitle.setText(e.label);
        appPkg.setText(e.pkg != null ? e.pkg : "");
        if (e.icon != null) {
            appIcon.setImageDrawable(e.icon);
        } else if (e.banner != null) {
            appIcon.setImageDrawable(e.banner);
        }

        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);

        Window w = dialog.getWindow();
        if (w != null) {
            float density = ctx.getResources().getDisplayMetrics().density;
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (520 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.72f);
        }

        View firstFocusable = null;

        // 1. Move to Front
        if (position > 0) {
            View row = addOptionRow(optionsContainer, R.drawable.ic_dialog_front, "Move to Front", "Pin to top favorite slot", false, v -> {
                dialog.dismiss();
                items.remove(position);
                items.add(0, e);
                saveOrder(ctx, items);
                notifyDataSetChanged();
            });
            if (firstFocusable == null) firstFocusable = row;
        }

        // 2. Move Left
        if (position > 0) {
            View row = addOptionRow(optionsContainer, R.drawable.ic_dialog_left, "Move Left", "Shift position earlier in the row", false, v -> {
                dialog.dismiss();
                Collections.swap(items, position, position - 1);
                saveOrder(ctx, items);
                notifyItemMoved(position, position - 1);
            });
            if (firstFocusable == null) firstFocusable = row;
        }

        // 3. Move Right
        if (position < items.size() - 1) {
            View row = addOptionRow(optionsContainer, R.drawable.ic_dialog_right, "Move Right", "Shift position later in the row", false, v -> {
                dialog.dismiss();
                Collections.swap(items, position, position + 1);
                saveOrder(ctx, items);
                notifyItemMoved(position, position + 1);
            });
            if (firstFocusable == null) firstFocusable = row;
        }

        // 4. App Info
        View infoRow = addOptionRow(optionsContainer, R.drawable.ic_dialog_info, "App Info", "View storage, cache and permissions", false, v -> {
            dialog.dismiss();
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {}
        });
        if (firstFocusable == null) firstFocusable = infoRow;

        // 5. Uninstall
        boolean canUninstall = false;
        if (e.pkg != null && !e.pkg.equals(ctx.getPackageName())) {
            try {
                PackageManager pm = ctx.getPackageManager();
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(e.pkg, 0);
                canUninstall = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
                        || (ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            } catch (Exception ex) {
                canUninstall = true;
            }
        }
        if (canUninstall) {
            addOptionRow(optionsContainer, R.drawable.ic_dialog_delete, "Uninstall", "Remove application from this TV", true, v -> {
                dialog.dismiss();
                try {
                    Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.pkg));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Exception ex) {
                    try {
                        Intent fallback = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + e.pkg));
                        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(fallback);
                    } catch (Exception ex2) {
                        try {
                            Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
                            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(settings);
                        } catch (Exception ex3) {
                            Toast.makeText(ctx, "Unable to uninstall " + e.label, Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });
        }

        dialog.show();

        // Entrance animation: scale up from center with fade
        if (w != null) {
            View decor = w.getDecorView();
            decor.setAlpha(0f);
            decor.setScaleX(0.92f);
            decor.setScaleY(0.92f);
            decor.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(250)
                    .setInterpolator(new DecelerateInterpolator(1.8f))
                    .start();
        }

        if (firstFocusable != null) {
            firstFocusable.requestFocus();
        }
    }

    private View addOptionRow(LinearLayout container, int iconRes, String title, String desc, boolean isDestructive, View.OnClickListener action) {
        View row = LayoutInflater.from(ctx).inflate(R.layout.dialog_option_row, container, false);
        ImageView tvIcon = row.findViewById(R.id.optionIcon);
        TextView tvTitle = row.findViewById(R.id.optionTitle);
        TextView tvDesc = row.findViewById(R.id.optionDesc);
        tvIcon.setImageResource(iconRes);
        tvTitle.setText(title);
        tvDesc.setText(desc);
        if (isDestructive) {
            row.setBackgroundResource(R.drawable.dialog_option_delete_focus_bg);
            tvIcon.setColorFilter(0xFFF87171);
            tvTitle.setTextColor(0xFFFCA5A5);
        }
        row.setOnClickListener(action);
        row.setOnFocusChangeListener((v, hasFocus) -> {
            float s = hasFocus ? 1.025f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(120).start();
        });
        container.addView(row);
        return row;
    }

    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // Keep at least xhdpi detail rather than downsampling artwork before the focus zoom.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                return resources.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        return ri.activityInfo.loadBanner(pm);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        int w = tileWidthPx(inner, MainActivity.COLUMNS, density), h = tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round(7 * density);
        name.setLayoutParams(nameLp);
        return new Holder(v, listener, animations, advancedEffects, reducedMotion);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.name);
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused && animations ? focusScale() : 1f);
        h.card.setScaleY(focused && animations ? focusScale() : 1f);
        h.card.setTranslationY(focused && advancedEffects ? -4f * ctx.getResources().getDisplayMetrics().density : 0f);
        h.card.setTranslationZ(focused && advancedEffects ? 8f * ctx.getResources().getDisplayMetrics().density : 0f);
        h.banner.setScaleX(focused && advancedEffects ? 1.035f : 1f);
        h.banner.setScaleY(focused && advancedEffects ? 1.035f : 1f);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }

        if (h.cwScrim != null) h.cwScrim.setVisibility(View.GONE);
        if (h.cwMeta != null) h.cwMeta.setVisibility(View.GONE);
        if (h.cwBadge != null) h.cwBadge.setVisibility(View.GONE);
        if (h.cwProgressTrack != null) h.cwProgressTrack.setVisibility(View.GONE);
        if (h.cwProgressBar != null) h.cwProgressBar.setVisibility(View.GONE);

        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            showAppDialog(h.getBindingAdapterPosition(), e);
            return true;
        });
        h.card.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                showAppDialog(h.getBindingAdapterPosition(), e);
                return true;
            }
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                    && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            int pos = h.getBindingAdapterPosition();
            return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
        });
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    private void launch(Entry e) {
        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (e.kind == Entry.WALLPAPER) {
            ctx.startActivity(new Intent(ctx, WallpaperPickerActivity.class));
            return;
        }
        if (e.kind == Entry.PRAYER) {
            ctx.startActivity(new Intent(ctx, PrayerTimesActivity.class));
            return;
        }
        if (e.kind == Entry.CONTINUE_WATCHING) {
            if (e.intentUri != null && !e.intentUri.isEmpty()) {
                try {
                    Intent intent = Intent.parseUri(e.intentUri, Intent.URI_INTENT_SCHEME);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent);
                    return;
                } catch (Exception ignored) {}
            }
            Intent i = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(e.pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            }
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
            if (advancedEffects && ctx instanceof Activity) {
                ((Activity) ctx).overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
            }
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    float focusScale() {
        return !animations ? 1f : advancedEffects ? 1.12f : 1.10f;
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry; a card's accent when it set one. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    public static int vividColor(Bitmap sourceBmp) {
        if (sourceBmp == null) return 0xFF312E81;
        int n = 12;
        Bitmap bmp = Bitmap.createScaledBitmap(sourceBmp, n, n, true);
        float[] hsv = new float[3];
        int best = 0xFF312E81;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        if (bmp != sourceBmp) bmp.recycle();
        if (bestScore < 0.25f) {
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    /** Corner radius for a tile of this width: 16dp at the design width, proportionally less when smaller. */
    static float cornerRadius(int tileWidthPx, float density) {
        return 16f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final View cwScrim;
        final View cwMeta;
        final TextView cwTitle;
        final TextView cwSub;
        final View cwBadge;
        final View cwProgressTrack;
        final View cwProgressBar;
        final boolean animations;
        final boolean advancedEffects;
        final boolean reducedMotion;

        Holder(View v, TileListener listener, boolean animations, boolean advancedEffects,
               boolean reducedMotion) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            cwScrim = v.findViewById(R.id.cwScrim);
            cwMeta = v.findViewById(R.id.cwMeta);
            cwTitle = v.findViewById(R.id.cwTitle);
            cwSub = v.findViewById(R.id.cwSub);
            cwBadge = v.findViewById(R.id.cwBadge);
            cwProgressTrack = v.findViewById(R.id.cwProgressTrack);
            cwProgressBar = v.findViewById(R.id.cwProgressBar);
            this.animations = animations;
            this.advancedEffects = advancedEffects;
            this.reducedMotion = reducedMotion;

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // 16dp at the design width, scaled down with the tile so small tiles keep the same shape.
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);



            card.setOnFocusChangeListener((view, focused) -> {
                view.animate().cancel();
                banner.animate().cancel();
                name.animate().cancel();
                float scale = !animations ? 1f : advancedEffects ? 1.12f : 1.10f;
                float s = focused ? scale : 1f;
                if (!animations) {
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                    view.setTranslationY(0f);
                    view.setTranslationZ(0f);
                    banner.setScaleX(1f);
                    banner.setScaleY(1f);
                    name.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
                    name.setAlpha(focused ? 1f : 0f);
                    name.setTranslationY(0f);
                } else if (reducedMotion) {
                    view.animate().scaleX(s).scaleY(s)
                            .translationY(focused && advancedEffects ? -4f * density : 0f)
                            .translationZ(focused && advancedEffects ? 8f * density : 0f)
                            .setDuration(focused ? 120 : 80)
                            .setInterpolator(new DecelerateInterpolator())
                            .withLayer().start();
                    if (focused) name.setVisibility(View.VISIBLE);
                    name.animate().alpha(focused ? 1f : 0f).setDuration(100)
                            .withEndAction(() -> { if (!focused) name.setVisibility(View.INVISIBLE); })
                            .start();
                } else {
                    view.animate().scaleX(s).scaleY(s)
                            .translationY(focused && advancedEffects ? -4f * density : 0f)
                            .translationZ(focused && advancedEffects ? 8f * density : 0f)
                            .setDuration(advancedEffects ? (focused ? 220 : 170) : (focused ? 260 : 180))
                            .setInterpolator(new OvershootInterpolator(focused ? (advancedEffects ? 0.7f : 1.6f) : 0f))
                            .withLayer().start();
                    if (advancedEffects) {
                        banner.animate().scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f)
                                .setDuration(280).start();
                    }
                    if (focused) name.setVisibility(View.VISIBLE);
                    name.animate().alpha(focused ? 1f : 0f)
                            .translationY(focused ? density * 4f : -density * 6f)
                            .setDuration(220)
                            .withEndAction(() -> { if (!focused) name.setVisibility(View.INVISIBLE); })
                            .start();
                }
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
