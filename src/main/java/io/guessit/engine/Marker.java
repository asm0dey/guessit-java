package io.guessit.engine;

public record Marker(String name, int start, int end, String raw) {
    public boolean contains(int pos) { return pos >= start && pos < end; }
    public boolean covers(int s, int e) { return s >= start && e <= end; }
    public int length() { return end - start; }
}
