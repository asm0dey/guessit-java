package io.guessit.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

public final class Holes {
    private Holes() {}

    public static final class Hole {
        public int start;
        public int end;
        public final String input;
        public final Function<String, String> formatter;

        public Hole(int start, int end, String input, Function<String, String> formatter) {
            this.start = start; this.end = end;
            this.input = input; this.formatter = formatter;
        }

        public String raw() { return input.substring(start, end); }
        public String value() { return formatter == null ? raw() : formatter.apply(raw()); }
        public boolean isEmpty() { var v = value(); return v == null || v.isEmpty(); }
        public int length() { return end - start; }

        public List<Hole> crop(List<Marker> markers) {
            var ret = new ArrayList<Hole>();
            ret.add(this);
            for (var m : markers) {
                var newRet = new ArrayList<Hole>();
                for (var h : ret) {
                    if (m.start() <= h.start && m.end() >= h.end) {
                        // marker fully covers hole - drop
                    } else if (m.start() >= h.start && m.end() <= h.end) {
                        var left = new Hole(h.start, m.start(), input, formatter);
                        var right = new Hole(m.end(), h.end, input, formatter);
                        if (left.length() > 0) newRet.add(left);
                        if (right.length() > 0) newRet.add(right);
                    } else if (m.end() >= h.end && m.start() < h.end && m.start() > h.start) {
                        h.end = m.start();
                        if (h.length() > 0) newRet.add(h);
                    } else if (m.start() <= h.start && m.end() > h.start && m.end() < h.end) {
                        h.start = m.end();
                        if (h.length() > 0) newRet.add(h);
                    } else {
                        newRet.add(h);
                    }
                }
                ret = newRet;
            }
            return ret;
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
                    if (!sub.isEmpty()) ret.add(sub);
                }
            }
            return ret;
        }
    }

    public static List<Hole> compute(String input, int start, int end,
                                     List<Match> allMatches,
                                     Predicate<Match> ignore,
                                     String seps,
                                     Function<String, String> formatter) {
        var matches = new ArrayList<>(allMatches);
        matches.sort(Comparator.comparingInt(Match::start));
        var active = new ArrayList<Match>();
        for (var m : matches) {
            if (ignore != null && ignore.test(m)) continue;
            if (m.end() <= start || m.start() >= end) continue;
            active.add(m);
        }

        var ret = new ArrayList<Hole>();
        Hole current = null;
        for (var pos = start; pos < end; pos++) {
            var inMatch = false;
            for (var m : active) {
                if (m.start() <= pos && pos < m.end()) { inMatch = true; break; }
            }
            if (current != null && seps != null && pos < input.length()
                    && seps.indexOf(input.charAt(pos)) >= 0) {
                current.end = pos;
                if (current.length() > 0) ret.add(current);
                current = null;
            } else if (!inMatch && current == null) {
                current = new Hole(pos, pos, input, formatter);
            } else if (inMatch && current != null) {
                current.end = pos;
                if (current.length() > 0) ret.add(current);
                current = null;
            }
        }
        if (current != null) {
            current.end = end;
            if (current.length() > 0) ret.add(current);
        }
        ret.removeIf(h -> { var v = h.value(); return v == null || v.isEmpty(); });
        return ret;
    }
}
