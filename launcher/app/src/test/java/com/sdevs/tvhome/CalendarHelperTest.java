package com.sdevs.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CalendarHelperTest {

    @Test
    public void testEventStatusFlags() {
        long now = 1000000L;

        CalendarHelper.Event ongoing = new CalendarHelper.Event();
        ongoing.begin = now - 5000L;
        ongoing.end = now + 5000L;
        ongoing.allDay = false;

        assertTrue(ongoing.isOngoing(now));
        assertFalse(ongoing.isPast(now));
        assertFalse(ongoing.isUpcoming(now));

        CalendarHelper.Event upcoming = new CalendarHelper.Event();
        upcoming.begin = now + 10000L;
        upcoming.end = now + 20000L;
        upcoming.allDay = false;

        assertFalse(upcoming.isOngoing(now));
        assertFalse(upcoming.isPast(now));
        assertTrue(upcoming.isUpcoming(now));

        CalendarHelper.Event past = new CalendarHelper.Event();
        past.begin = now - 20000L;
        past.end = now - 10000L;
        past.allDay = false;

        assertFalse(past.isOngoing(now));
        assertTrue(past.isPast(now));
        assertFalse(past.isUpcoming(now));
    }

    @Test
    public void testGetNextOrCurrentEvent() {
        long now = 1000000L;
        List<CalendarHelper.Event> events = new ArrayList<>();

        // Past event
        CalendarHelper.Event past = new CalendarHelper.Event();
        past.title = "Past Event";
        past.begin = now - 20000L;
        past.end = now - 10000L;
        events.add(past);

        // Upcoming event
        CalendarHelper.Event upcoming = new CalendarHelper.Event();
        upcoming.title = "Yoga Class";
        upcoming.begin = now + 3600000L; // in 1 hour
        upcoming.end = now + 7200000L;
        events.add(upcoming);

        CalendarHelper.Event selected = CalendarHelper.getNextOrCurrentEvent(events, now);
        assertNotNull(selected);
        assertEquals("Yoga Class", selected.title);

        // Add ongoing event: should take priority over upcoming
        CalendarHelper.Event ongoing = new CalendarHelper.Event();
        ongoing.title = "Team Standup";
        ongoing.begin = now - 10000L;
        ongoing.end = now + 20000L;
        events.add(0, ongoing);

        selected = CalendarHelper.getNextOrCurrentEvent(events, now);
        assertNotNull(selected);
        assertEquals("Team Standup", selected.title);
    }

    @Test
    public void testEmptyEventsReturnsNull() {
        assertNull(CalendarHelper.getNextOrCurrentEvent(null, 1000L));
        assertNull(CalendarHelper.getNextOrCurrentEvent(new ArrayList<>(), 1000L));
    }
}
