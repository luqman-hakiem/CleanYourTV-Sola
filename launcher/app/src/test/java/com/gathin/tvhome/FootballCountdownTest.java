package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.Instant;

/** The countdown on the nearest fixture line (HomeWidgets.countdown). */
public class FootballCountdownTest {

    private static final Instant NOW = Instant.parse("2026-11-13T09:38:00Z");

    private static String at(String kickOff) {
        return HomeWidgets.countdown(Instant.parse(kickOff), NOW);
    }

    @Test
    public void daysAwayReadsInDays() {
        assertEquals("9 HARI 6 JAM", at("2026-11-22T16:30:00Z"));
        assertEquals("2 HARI 5 JAM", at("2026-11-15T14:38:00Z"));
    }

    @Test
    public void hoursAwayReadsInHoursAndMinutes() {
        assertEquals("5 JAM 12 MNT", at("2026-11-13T14:50:00Z"));
        assertEquals("1 JAM 0 MNT", at("2026-11-13T10:38:00Z"));
    }

    @Test
    public void underAnHourReadsInMinutes() {
        assertEquals("38 MNT LAGI", at("2026-11-13T10:16:00Z"));
        assertEquals("1 MNT LAGI", at("2026-11-13T09:39:00Z"));
    }

    @Test
    public void kickOffAndPastTimesSaySooner() {
        assertEquals("SEGERA", at("2026-11-13T09:38:00Z"));
        assertEquals("SEGERA", at("2026-11-13T09:00:00Z"));
    }
}
