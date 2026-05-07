package io.guessit.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public final class Holes {
    private Holes() {}

    public static final class Hole {
        public int start;
        public int end;
        public final String input;
        public final UnaryOperator<String> formatter;

        public Hole(int start, int end, String input, UnaryOperator<String> formatter) {
            this.start = start; this.end = end;
            this.input = input; this.formatter = formatter;
        }

        public String raw() { return input.substring(start, end); }
        public String value() { return formatter == null ? raw() : formatter.apply(raw()); }
        public boolean isEmpty() { var v = value(); return v != null && !v.isEmpty(); }
        public int length() { return end - start; }

        public List<Hole> crop(List<Marker> markers) {
            var ret = new ArrayList<Hole>();
            ret.add(this);
            for (var m : markers) {
                var newRet = new ArrayList<Hole>();
                for (var h : ret) applyMarkerToHole(m, h, newRet);
                ret = newRet;
            }
            return ret;
        }

        private void applyMarkerToHole(Marker m, Hole h, List<Hole> newRet) {
            if (m.start() <= h.start && m.end() >= h.end) return; // fully covers — drop
            if (m.start() >= h.start && m.end() <= h.end) {
                splitHole(m, h, newRet);
                return;
            }
            if (m.end() >= h.end && m.start() < h.end) {
                h.end = m.start();
                if (h.length() > 0) newRet.add(h);
                return;
            }
            if (m.start() <= h.start && m.end() > h.start) {
                h.start = m.end();
                if (h.length() > 0) newRet.add(h);
                return;
            }
            newRet.add(h);
        }

        private void splitHole(Marker m, Hole h, List<Hole> newRet) {
            var left = new Hole(h.start, m.start(), input, formatter);
            var right = new Hole(m.end(), h.end, input, formatter);
            if (left.length() > 0) newRet.add(left);
            if (right.length() > 0) newRet.add(right);
        }

        public List<Hole> split(String seps) {
            var ret = new ArrayList<Hole>();
            var raw = raw();
            int i = 0;
            while (i < raw.length()) {
                while (i < raw.length() && seps.indexOf(raw.charAt(i)) >= 0) i++;
                int s = i;
                while (i < raw.length() && seps.indexOf(raw.charAt(i)) < 0) i++;
                if (s < i) {
                    var sub = new Hole(start + s, start + i, input, formatter);
                    if (sub.isEmpty()) ret.add(sub);
                }
            }
            return ret;
        }
    }

    public static List<Hole> compute(String input, int start, int end,
                                     List<Match> allMatches,
                                     Predicate<Match> ignore,
                                     String seps,
                                     UnaryOperator<String> formatter) {
        var active = collectActiveMatches(allMatches, ignore, start, end);
        var ret = new ArrayList<Hole>();
        Hole current = null;
        for (var pos = start; pos < end; pos++) {
            current = stepPosition(pos, current, active, input, seps, formatter, ret);
        }
        if (current != null) {
            current.end = end;
            if (current.isEmpty()) ret.add(current);
        }
        ret.removeIf(h -> { var v = h.value(); return v == null || v.isEmpty(); });
        return ret;
    }

    private static List<Match> collectActiveMatches(List<Match> allMatches, Predicate<Match> ignore,
                                                    int start, int end) {
        var matches = new ArrayList<>(allMatches);
        matches.sort(Comparator.comparingInt(Match::start));
        var active = new ArrayList<Match>();
        for (var m : matches) {
            if (ignore != null && ignore.test(m)) continue;
            if (m.end() <= start || m.start() >= end) continue;
            active.add(m);
        }
        return active;
    }

    private static boolean inMatch(List<Match> active, int pos) {
        for (var m : active) {
            if (m.start() <= pos && pos < m.end()) return true;
        }
        return false;
    }

    private static Hole stepPosition(int pos, Hole current, List<Match> active, String input,
                                     String seps, UnaryOperator<String> formatter, ArrayList<Hole> ret) {
        boolean inM = inMatch(active, pos);
        if (current != null && seps != null && pos < input.length() && seps.indexOf(input.charAt(pos)) >= 0) {
            return closeHole(current, pos, ret);
        }
        if (!inM && current == null) {
            return new Hole(pos, pos, input, formatter);
        }
        if (inM && current != null) {
            return closeHole(current, pos, ret);
        }
        return current;
    }

    private static Hole closeHole(Hole current, int pos, ArrayList<Hole> ret) {
        current.end = pos;
        if (current.isEmpty()) ret.add(current);
        return null;
    }
}
