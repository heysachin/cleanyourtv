package com.sdevs.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Adds a gap below the first (dock) row and draws the tvOS style band behind it.
 * Also provides clear section spacing and typography headers for "CONTINUE WATCHING"
 * and "APPS & TOOLS".
 */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final boolean drawBand;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private AppAdapter adapter;

    DockDecoration(int dockSize, float density, boolean drawBand) {
        this.dockSize = dockSize;
        this.density = density;
        this.drawBand = drawBand;
        band.setColor(0xFF141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(0x1FFFFFFF);

        headerPaint.setColor(0xCCFFFFFF);
        headerPaint.setTextSize(11.5f * density);
        headerPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        headerPaint.setLetterSpacing(0.12f);
    }

    void setAdapter(AppAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos < 0) return;

        int actualDock = adapter != null ? adapter.getDockSize() : dockSize;
        if (pos < actualDock) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        } else {
            outRect.top = (int) (8 * density);
            outRect.bottom = (int) (8 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (!drawBand) return;

        int actualDock = adapter != null ? adapter.getDockSize() : dockSize;
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= actualDock) continue;
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (any) {
            float pad = 14 * density;
            rect.inset(-pad, -pad);
            float radius = 26 * density;
            c.drawRoundRect(rect, radius, radius, band);
            c.drawRoundRect(rect, radius, radius, edge);
        }
    }
}
