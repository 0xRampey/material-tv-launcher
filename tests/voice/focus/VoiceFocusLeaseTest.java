package com.rampey.materialtv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class VoiceFocusLeaseTest {
    private static int checks;

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static final class Focus implements VoiceFocusLease.Request {
        final List<String> events;
        final String name;
        boolean granted = true, requestThrows, abandonThrows;
        int requests, abandons;
        Runnable onAbandon;

        Focus(List<String> events, String name) { this.events = events; this.name = name; }
        @Override public boolean acquire() {
            requests++;
            events.add(name + ":request");
            if (requestThrows) throw new IllegalStateException("request failed");
            return granted;
        }
        @Override public void abandon() {
            abandons++;
            events.add(name + ":abandon");
            if (onAbandon != null) onAbandon.run();
            if (abandonThrows) throw new IllegalStateException("abandon failed");
        }
    }

    public static void main(String[] ignored) {
        List<String> events = new ArrayList<>();
        VoiceFocusLease lease = new VoiceFocusLease();
        Focus first = new Focus(events, "first");
        check(lease.acquire(1, first), "granted");
        events.add("recording complete");
        events.add("recognition complete");
        events.add("model complete");
        check(first.abandons == 0, "focus survives capture, ASR and model stages");
        lease.release(1);
        events.add("pause action");
        lease.release(1);
        lease.release();
        check(first.abandons == 1, "action/cancel/dismiss release only once");
        check(events.indexOf("first:abandon") < events.indexOf("pause action"),
                "focus restoration is requested before a media action");

        events.clear();
        Focus old = new Focus(events, "old"), newer = new Focus(events, "new");
        lease.acquire(2, old);
        lease.acquire(3, newer);
        check(events.equals(Arrays.asList("old:request", "old:abandon", "new:request")),
                "new press abandons old focus before acquiring its own");
        lease.release(2);
        check(newer.abandons == 0, "stale model completion cannot release new turn");
        lease.release(3);
        check(newer.abandons == 1, "current completion releases new turn");

        for (String ending : new String[]{"no speech", "empty transcript", "capture error", "model error",
                "no action", "Back", "Home", "system dismissal", "settings", "destroy", "ready error"}) {
            Focus f = new Focus(events, ending);
            lease.acquire(4, f);
            lease.release();
            lease.release(4);
            check(f.abandons == 1, ending + " releases once");
        }

        Focus denied = new Focus(events, "denied");
        denied.granted = false;
        check(!lease.acquire(5, denied), "denial reported");
        check(denied.abandons == 1, "denied/delayed request withdrawn");
        lease.release();
        check(denied.abandons == 1, "denied request not retained");

        Focus requestFailure = new Focus(events, "requestFailure");
        requestFailure.requestThrows = true;
        try {
            lease.acquire(6, requestFailure);
            throw new AssertionError("request exception expected");
        } catch (IllegalStateException expected) {
            check(requestFailure.abandons == 1, "request exception cleans up possible focus ownership");
        }
        lease.release();
        check(requestFailure.abandons == 1, "failed request not retained");

        Focus reentrant = new Focus(events, "reentrant");
        reentrant.onAbandon = lease::release;
        lease.acquire(7, reentrant);
        lease.release();
        check(reentrant.abandons == 1, "reentrant cancellation cannot abandon twice");

        Focus abandonFailure = new Focus(events, "abandonFailure");
        abandonFailure.abandonThrows = true;
        lease.acquire(8, abandonFailure);
        try {
            lease.release();
            throw new AssertionError("abandon exception expected");
        } catch (IllegalStateException expected) {
            check(abandonFailure.abandons == 1, "abandon exception remains observable");
        }
        lease.release();
        check(abandonFailure.abandons == 1, "state cleared even if backend abandon fails");
        Focus recovery = new Focus(events, "recovery");
        check(lease.acquire(9, recovery), "next turn can acquire after failure");
        lease.release(8);
        check(recovery.abandons == 0, "stale failure cleanup cannot affect recovery");
        lease.release(9);
        check(recovery.abandons == 1, "recovery releases normally");
        System.out.println("PASS " + checks + " focus lifecycle checks");
    }
}
