package at.technikum.thesis.runner;

public record Interval(double from, double to) {

    public boolean contains(double time) {
        return time >= from && time < to;
    }

    public double lengthMillis() {
        return to - from;
    }
}
