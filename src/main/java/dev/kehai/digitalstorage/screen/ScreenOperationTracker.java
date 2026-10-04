package dev.kehai.digitalstorage.screen;

/** One outstanding menu operation. Timeout never grants permission to replay a mutation. */
public final class ScreenOperationTracker {
    private static final int TIMEOUT_TICKS = 200;
    private long revision;
    private int elapsed;
    private boolean outstanding;
    private boolean timedOut;

    public void begin(long responseRevision) {
        if (outstanding) throw new IllegalStateException("A screen operation is already outstanding");
        revision = responseRevision;
        elapsed = 0;
        timedOut = false;
        outstanding = true;
    }

    public boolean observe(long responseRevision) {
        if (!outstanding || responseRevision <= revision) return false;
        outstanding = false;
        timedOut = false;
        return true;
    }

    public boolean tick() {
        if (!outstanding || timedOut || ++elapsed < TIMEOUT_TICKS) return false;
        timedOut = true;
        return true;
    }

    public boolean outstanding() { return outstanding; }
    public boolean pending() { return outstanding && !timedOut; }
    public boolean timedOut() { return timedOut; }

    static void runSelfTest() {
        var tracker = new ScreenOperationTracker();
        tracker.begin(42);
        if (tracker.observe(42) || tracker.observe(41))
            throw new IllegalStateException("A content broadcast completed a screen operation");
        for (int i = 0; i < TIMEOUT_TICKS - 1; i++) {
            if (tracker.tick()) throw new IllegalStateException("Screen operation timed out early");
        }
        if (!tracker.tick() || tracker.pending() || !tracker.outstanding() || tracker.tick())
            throw new IllegalStateException("Screen timeout did not retain its outstanding operation");
        try {
            tracker.begin(42);
            throw new IllegalStateException("Timed-out mutation could be replayed");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().equals("A screen operation is already outstanding")) throw expected;
        }
        if (!tracker.observe(43) || tracker.timedOut() || tracker.outstanding() || tracker.observe(43))
            throw new IllegalStateException("A late response was lost or consumed more than once");
        tracker.begin(43);
        if (!tracker.pending() || !tracker.observe(44))
            throw new IllegalStateException("Screen operation could not recover after a late response");
    }
}
