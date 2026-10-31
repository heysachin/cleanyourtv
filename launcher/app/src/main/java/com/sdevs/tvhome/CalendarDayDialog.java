package com.sdevs.tvhome;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CalendarDayDialog {

    public static void show(Context ctx) {
        if (!CalendarHelper.hasPermission(ctx)) {
            return;
        }

        List<CalendarHelper.Event> events = CalendarHelper.getTodayEvents(ctx);
        long now = System.currentTimeMillis();

        View panel = LayoutInflater.from(ctx).inflate(R.layout.dialog_calendar_day, null);
        TextView titleView = panel.findViewById(R.id.dialogCalendarTitle);
        TextView subtitleView = panel.findViewById(R.id.dialogCalendarSubtitle);
        LinearLayout eventsContainer = panel.findViewById(R.id.dialogEventsContainer);
        TextView emptyView = panel.findViewById(R.id.dialogCalendarEmpty);

        // Header
        SimpleDateFormat dayFormat = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault());
        titleView.setText(dayFormat.format(new Date(now)));

        int count = events.size();
        if (count == 0) {
            subtitleView.setText("No events scheduled");
            emptyView.setVisibility(View.VISIBLE);
            eventsContainer.setVisibility(View.GONE);
        } else {
            subtitleView.setText(count + (count == 1 ? " event scheduled today" : " events scheduled today"));
            emptyView.setVisibility(View.GONE);
            eventsContainer.setVisibility(View.VISIBLE);

            java.text.DateFormat timeFormat = DateFormat.getTimeFormat(ctx);
            View firstFocusable = null;

            for (CalendarHelper.Event e : events) {
                View row = LayoutInflater.from(ctx).inflate(R.layout.dialog_calendar_event_row, eventsContainer, false);
                View colorBar = row.findViewById(R.id.eventColorBar);
                TextView timeView = row.findViewById(R.id.eventTime);
                TextView eventTitle = row.findViewById(R.id.eventTitle);
                TextView eventSubtitle = row.findViewById(R.id.eventSubtitle);
                TextView statusBadge = row.findViewById(R.id.eventStatusBadge);

                // Set calendar / event color
                if (colorBar != null) {
                    GradientDrawable barBg = new GradientDrawable();
                    barBg.setColor(e.color != 0 ? e.color : 0xFF6366F1);
                    barBg.setCornerRadius(ctx.getResources().getDisplayMetrics().density * 3);
                    colorBar.setBackground(barBg);
                }

                // Time
                if (e.allDay) {
                    timeView.setText("ALL DAY");
                } else {
                    String startStr = timeFormat.format(new Date(e.begin));
                    String endStr = timeFormat.format(new Date(e.end));
                    timeView.setText(startStr + " – " + endStr);
                }

                // Title
                eventTitle.setText(e.title);

                // Subtitle (location or calendar name)
                StringBuilder sub = new StringBuilder();
                if (e.location != null && !e.location.trim().isEmpty()) {
                    sub.append(e.location.trim());
                }
                if (e.calendarName != null && !e.calendarName.trim().isEmpty()) {
                    if (sub.length() > 0) sub.append(" · ");
                    sub.append(e.calendarName.trim());
                }
                if (sub.length() > 0) {
                    eventSubtitle.setText(sub.toString());
                    eventSubtitle.setVisibility(View.VISIBLE);
                } else {
                    eventSubtitle.setVisibility(View.GONE);
                }

                // Status Badge
                if (e.isOngoing(now)) {
                    statusBadge.setText("NOW");
                    statusBadge.setTextColor(0xFF34D399); // emerald
                    statusBadge.setBackgroundResource(R.drawable.calendar_badge_now);
                    statusBadge.setVisibility(View.VISIBLE);
                } else if (e.isUpcoming(now)) {
                    long diff = e.begin - now;
                    long mins = diff / 60000;
                    if (mins < 60) {
                        statusBadge.setText("IN " + Math.max(1, mins) + "M");
                    } else {
                        long hours = mins / 60;
                        statusBadge.setText("IN " + hours + "H");
                    }
                    statusBadge.setTextColor(0xFF93C5FD); // sky blue
                    statusBadge.setBackgroundResource(R.drawable.calendar_badge_upcoming);
                    statusBadge.setVisibility(View.VISIBLE);
                } else if (e.isPast(now)) {
                    statusBadge.setText("DONE");
                    statusBadge.setTextColor(0xFF6B7280);
                    statusBadge.setBackgroundResource(R.drawable.calendar_badge_done);
                    statusBadge.setVisibility(View.VISIBLE);
                } else {
                    statusBadge.setVisibility(View.GONE);
                }

                eventsContainer.addView(row);
                if (firstFocusable == null) {
                    firstFocusable = row;
                }
            }
        }

        Dialog dialog = new Dialog(ctx, R.style.Theme_TvHome_Picker);
        dialog.setContentView(panel);

        Window w = dialog.getWindow();
        if (w != null) {
            float density = ctx.getResources().getDisplayMetrics().density;
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (580 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.72f);
        }

        dialog.show();

        if (eventsContainer.getChildCount() > 0) {
            eventsContainer.getChildAt(0).requestFocus();
        }
    }
}
