package com.rampey.materialtv;

/** Main-thread ownership of one turn's focus request. Stale model callbacks cannot release a newer turn. */
final class VoiceFocusLease {
    interface Request {
        boolean acquire();
        void abandon();
    }

    private int owner;
    private Request request;

    boolean acquire(int turn, Request next) {
        release();
        owner = turn;
        request = next;
        boolean granted = false;
        try {
            granted = next.acquire();
            return granted;
        } finally {
            // Also clean up a denied/delayed request or an acquire exception.
            if (!granted) release(turn);
        }
    }

    void release(int turn) {
        if (request != null && owner == turn) release();
    }

    void release() {
        Request previous = request;
        request = null; // Clear first: duplicate/reentrant cancellation must not abandon twice.
        if (previous != null) previous.abandon();
    }
}
