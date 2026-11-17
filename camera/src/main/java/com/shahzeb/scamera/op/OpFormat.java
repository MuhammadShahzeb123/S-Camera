package com.shahzeb.scamera.op;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Formatting helpers and standard value ladders for manual camera controls. */
public final class OpFormat {
    private OpFormat() {
    }

    public static String zoom(float z) {
        String t = String.format(Locale.US, "%.1f", z);
        if( t.endsWith(".0") )
            t = t.substring(0, t.length()-2);
        return t + "x";
    }

    /** Shutter speed in photographic notation, e.g. 1/125, 1/4, 0.5", 2", 30". */
    public static String shutter(long ns) {
        double s = ns / 1.0e9;
        if( s >= 0.95 ) {
            if( Math.abs(s - Math.round(s)) < 0.05 )
                return Math.round(s) + "\"";
            return String.format(Locale.US, "%.1f\"", s);
        }
        if( s >= 0.3 ) {
            return String.format(Locale.US, "%.1f\"", s);
        }
        long denom = Math.round(1.0 / s);
        return "1/" + denom;
    }

    public static String ev(float ev) {
        if( Math.abs(ev) < 0.05f )
            return "0";
        return String.format(Locale.US, "%+.1f", ev);
    }

    public static String focus(float diopters) {
        if( diopters <= 0.0001f )
            return "∞";
        float m = 1.0f / diopters;
        if( m >= 1.0f )
            return String.format(Locale.US, "%.1fm", m);
        return String.format(Locale.US, "%dcm", Math.round(m * 100));
    }

    /** Standard photographic shutter ladder (in ns), clipped to [min,max]. */
    public static List<Long> shutterLadder(long min, long max) {
        double [] seconds = {
                1/16000.0, 1/12800.0, 1/10000.0, 1/8000.0, 1/6400.0, 1/5000.0, 1/4000.0, 1/3200.0, 1/2500.0, 1/2000.0,
                1/1600.0, 1/1250.0, 1/1000.0, 1/800.0, 1/640.0, 1/500.0, 1/400.0, 1/320.0, 1/250.0, 1/200.0,
                1/160.0, 1/125.0, 1/100.0, 1/80.0, 1/60.0, 1/50.0, 1/48.0, 1/40.0, 1/30.0, 1/25.0, 1/20.0, 1/15.0,
                1/13.0, 1/10.0, 1/8.0, 1/6.0, 1/5.0, 1/4.0, 0.3, 0.4, 0.5, 0.6, 0.8, 1, 1.3, 1.6, 2, 2.5, 3.2, 4, 5,
                6, 8, 10, 13, 15, 20, 25, 30, 40, 50, 60
        };
        List<Long> result = new ArrayList<>();
        for(double s : seconds) {
            long ns = Math.round(s * 1.0e9);
            if( ns >= min && ns <= max )
                result.add(ns);
        }
        if( result.isEmpty() ) {
            result.add(min);
            if( max != min )
                result.add(max);
        }
        else {
            // make sure the extremes are reachable
            if( result.get(0) > min * 1.05 )
                result.add(0, min);
            if( result.get(result.size()-1) < max * 0.95 )
                result.add(max);
        }
        return result;
    }

    /** ISO ladder in 1/3 stops, clipped to [min,max]. */
    public static List<Integer> isoLadder(int min, int max) {
        int [] isos = {25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600,
                2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 51200};
        List<Integer> result = new ArrayList<>();
        for(int iso : isos) {
            if( iso >= min && iso <= max )
                result.add(iso);
        }
        if( result.isEmpty() || result.get(0) > min ) {
            result.add(0, min);
        }
        if( result.get(result.size()-1) < max )
            result.add(max);
        return result;
    }

    /** White balance temperatures in kelvin. */
    public static List<Integer> wbLadder(int min, int max) {
        List<Integer> result = new ArrayList<>();
        for(int k = 2000; k <= 10000; k += 100) {
            if( k >= min && k <= max )
                result.add(k);
        }
        if( result.isEmpty() ) {
            result.add(min);
            result.add(max);
        }
        return result;
    }
}
