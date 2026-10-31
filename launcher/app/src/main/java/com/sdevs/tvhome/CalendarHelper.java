package com.sdevs.tvhome;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.text.format.DateFormat;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class CalendarHelper {
    private static final String TAG = "CalendarHelper";

    public static class Event {
        public long id;
        public long eventId;
        public String title;
        public long begin;
        public long end;
        public boolean allDay;
        public String location;
        public String calendarName;
        public int color;
        public int status;

        public boolean isOngoing(long now) {
            return !allDay && now >= begin && now < end;
        }

        public boolean isPast(long now) {
            return allDay ? false : now >= end;
        }

        public boolean isUpcoming(long now) {
            return !allDay && now < begin;
        }
    }

    public static boolean hasPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static List<Event> getTodayEvents(Context context) {
        if (!hasPermission(context)) return Collections.emptyList();

        List<Event> list = new ArrayList<>();
        try {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long startOfDay = cal.getTimeInMillis();

            cal.add(Calendar.DAY_OF_YEAR, 1);
            long endOfDay = cal.getTimeInMillis();

            Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(builder, startOfDay);
            ContentUris.appendId(builder, endOfDay);
            Uri uri = builder.build();

            String[] projection = new String[] {
                    CalendarContract.Instances._ID,
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Instances.DISPLAY_COLOR,
                    CalendarContract.Instances.VISIBLE,
                    CalendarContract.Events.STATUS
            };

            String selection = CalendarContract.Instances.VISIBLE + " = 1";
            String sortOrder = CalendarContract.Instances.BEGIN + " ASC, " + CalendarContract.Instances.END + " ASC";

            try (Cursor c = context.getContentResolver().query(uri, projection, selection, null, sortOrder)) {
                if (c != null) {
                    int colId = c.getColumnIndex(CalendarContract.Instances._ID);
                    int colEventId = c.getColumnIndex(CalendarContract.Instances.EVENT_ID);
                    int colTitle = c.getColumnIndex(CalendarContract.Instances.TITLE);
                    int colBegin = c.getColumnIndex(CalendarContract.Instances.BEGIN);
                    int colEnd = c.getColumnIndex(CalendarContract.Instances.END);
                    int colAllDay = c.getColumnIndex(CalendarContract.Instances.ALL_DAY);
                    int colLoc = c.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION);
                    int colCalName = c.getColumnIndex(CalendarContract.Instances.CALENDAR_DISPLAY_NAME);
                    int colColor = c.getColumnIndex(CalendarContract.Instances.DISPLAY_COLOR);
                    int colStatus = c.getColumnIndex(CalendarContract.Events.STATUS);

                    while (c.moveToNext()) {
                        int status = colStatus >= 0 ? c.getInt(colStatus) : 1;
                        if (status == CalendarContract.Events.STATUS_CANCELED) continue;

                        Event e = new Event();
                        e.id = colId >= 0 ? c.getLong(colId) : 0;
                        e.eventId = colEventId >= 0 ? c.getLong(colEventId) : 0;
                        e.title = colTitle >= 0 ? c.getString(colTitle) : "";
                        if (e.title == null || e.title.trim().isEmpty()) {
                            e.title = "Untitled Event";
                        }
                        e.begin = colBegin >= 0 ? c.getLong(colBegin) : 0;
                        e.end = colEnd >= 0 ? c.getLong(colEnd) : 0;
                        e.allDay = colAllDay >= 0 && c.getInt(colAllDay) == 1;
                        e.location = colLoc >= 0 ? c.getString(colLoc) : "";
                        e.calendarName = colCalName >= 0 ? c.getString(colCalName) : "";
                        e.color = colColor >= 0 ? c.getInt(colColor) : 0xFF6366F1;
                        e.status = status;

                        list.add(e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying calendar instances", e);
        }
        return list;
    }

    public static Event getNextOrCurrentEvent(List<Event> events, long now) {
        if (events == null || events.isEmpty()) return null;

        // 1. Look for currently ongoing event first
        for (Event e : events) {
            if (e.isOngoing(now)) return e;
        }

        // 2. Look for upcoming event today
        for (Event e : events) {
            if (e.isUpcoming(now)) return e;
        }

        // 3. Look for any all-day event
        for (Event e : events) {
            if (e.allDay) return e;
        }

        return null;
    }

    public static String formatChipText(Context context, Event event, long now) {
        if (event == null) return "No more events today";

        java.text.DateFormat timeFormat = DateFormat.getTimeFormat(context);

        if (event.allDay) {
            return "Today · " + event.title;
        }

        if (event.isOngoing(now)) {
            String endStr = timeFormat.format(new Date(event.end));
            return "Now · " + event.title + " (ends " + endStr + ")";
        }

        if (event.isUpcoming(now)) {
            long diff = event.begin - now;
            long mins = diff / 60000;
            String timeStr = timeFormat.format(new Date(event.begin));
            if (mins < 60) {
                return "In " + Math.max(1, mins) + "m · " + event.title + " (" + timeStr + ")";
            } else {
                long hours = mins / 60;
                long remMins = mins % 60;
                String inStr = hours + "h" + (remMins > 0 ? " " + remMins + "m" : "");
                return timeStr + " · " + event.title + " (in " + inStr + ")";
            }
        }

        return event.title;
    }
}
