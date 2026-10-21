package com.rampey.voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class StreamingDecoderTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void until(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
        check(condition.getAsBoolean(), message);
    }
    private static final class Fake implements StreamingDecoder.Backend {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final List<Float> received = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch insideDecode = new CountDownLatch(1);
        final CountDownLatch allowDecode = new CountDownLatch(1);
        volatile int creates, releases, decodes, finished;
        volatile boolean pending, tailPending, blockFirst;
        volatile String words = "";
        volatile Thread owner;
        Fake(boolean blockFirst) { this.blockFirst = blockFirst; }
        private void own() {
            if (owner == null) owner = Thread.currentThread();
            check(owner == Thread.currentThread(), "backend accessed off its worker");
        }
        public void create() {
            own(); creates++; words = ""; pending = false; tailPending = false;
            calls.add("create");
        }
        public void accept(float[] samples) {
            own(); received.add(samples[0]); pending = true;
            calls.add("accept:" + samples[0]);
        }
        public boolean ready() { own(); return pending || tailPending; }
        public void decode() {
            own(); decodes++; calls.add("decode");
            if (blockFirst && decodes == 1) {
                insideDecode.countDown();
                try {
                    if (!allowDecode.await(3, TimeUnit.SECONDS)) throw new AssertionError("test blocked");
                } catch (InterruptedException e) { throw new AssertionError(e); }
            }
            if (tailPending) { words += " final"; tailPending = false; }
            else { words = "word" + received.get(received.size() - 1); pending = false; }
        }
        public String text() { own(); return words; }
        public void finishInput() {
            own(); finished++; tailPending = true; calls.add("finish");
        }
        public void release() { own(); releases++; calls.add("release"); }
    }

    private static StreamingDecoder decoder(Fake f, AtomicBoolean active, List<Runnable> events,
                                             List<String> delivered) {
        return new StreamingDecoder(f, active::get, events::add, delivered::add);
    }
    private static void runEvents(List<Runnable> events) {
        List<Runnable> copy;
        synchronized (events) { copy = new ArrayList<>(events); events.clear(); }
        for (Runnable event : copy) event.run();
    }

    private static void slowDecoderAndFinalization() throws Exception {
        Fake f = new Fake(true);
        AtomicBoolean active = new AtomicBoolean(true);
        List<Runnable> events = Collections.synchronizedList(new ArrayList<>());
        List<String> words = new ArrayList<>();
        StreamingDecoder d = decoder(f, active, events, words);
        d.offer(new float[]{1});
        check(f.insideDecode.await(1, TimeUnit.SECONDS), "decode entered");
        float[] reused = new float[320];
        reused[0] = 2;
        long start = System.nanoTime();
        check(d.offer(reused), "second frame accepted while native decode blocked");
        reused[0] = 99;
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 100,
                "capture offer must not wait for decode");
        d.finish();
        f.allowDecode.countDown();
        check(d.await(2000), "finished successfully");
        check(f.received.equals(List.of(1f, 2f)), "queued samples copied and ordered");
        check(f.finished == 1 && f.releases == 1, "finish and release exactly once");
        check(d.text().equals("word2.0 final"), "last words decoded after inputFinished");
        check(f.calls.indexOf("finish") < f.calls.lastIndexOf("decode"), "tail decoded after finish");
        check(d.peakQueueMs() >= 20, "queue backlog measured");
        runEvents(events);
        check(words.get(words.size() - 1).equals(d.text()), "final words delivered");
    }

    private static void resetDuringDecode() throws Exception {
        Fake f = new Fake(true);
        AtomicBoolean active = new AtomicBoolean(true);
        List<Runnable> events = Collections.synchronizedList(new ArrayList<>());
        List<String> words = new ArrayList<>();
        StreamingDecoder d = decoder(f, active, events, words);
        d.offer(new float[]{1});
        check(f.insideDecode.await(1, TimeUnit.SECONDS), "old decode entered");
        d.reset();
        check(d.text().isEmpty(), "reset clears text immediately");
        d.offer(new float[]{2});
        d.finish();
        f.allowDecode.countDown();
        check(d.await(2000), "reset stream finished");
        check(f.creates == 2 && f.releases == 2, "old and new stream each released once");
        runEvents(events);
        check(words.stream().noneMatch(w -> w.contains("1.0")), "stale decode never published");
        check(d.text().equals("word2.0 final"), "new stream owns final text");
    }

    private static void stalePostedCallback() throws Exception {
        Fake f = new Fake(false);
        AtomicBoolean active = new AtomicBoolean(true);
        List<Runnable> events = Collections.synchronizedList(new ArrayList<>());
        List<String> words = new ArrayList<>();
        StreamingDecoder d = decoder(f, active, events, words);
        d.offer(new float[]{1});
        until(() -> !events.isEmpty(), "old callback queued");
        d.reset();
        runEvents(events);
        check(words.isEmpty(), "already-posted old callback rejected after reset");
        d.offer(new float[]{2});
        d.finish();
        check(d.await(2000), "new generation finished");
        active.set(false);
        runEvents(events);
        check(words.isEmpty(), "callbacks reject inactive session");
    }

    private static void cancellationAndOverflow() throws Exception {
        for (boolean overflow : new boolean[]{false, true}) {
            Fake f = new Fake(true);
            AtomicBoolean active = new AtomicBoolean(true);
            List<Runnable> events = Collections.synchronizedList(new ArrayList<>());
            List<String> words = new ArrayList<>();
            StreamingDecoder d = decoder(f, active, events, words);
            d.offer(new float[]{1});
            check(f.insideDecode.await(1, TimeUnit.SECONDS), "decode entered");
            if (overflow) {
                for (int i = 0; i < 800; i++) check(d.offer(new float[320]), "bounded queue frame accepted");
                check(!d.offer(new float[320]), "overflow rejected without blocking");
                check(d.error() != null && d.error().contains("fell behind"), "overflow error distinct");
            } else {
                d.offer(new float[]{2});
                d.cancel();
                check(d.error() == null, "ordinary cancellation has no failure error");
            }
            check(!d.await(10), "cancelled/overflow result fails closed");
            check(!d.offer(new float[]{3}), "new audio rejected");
            f.allowDecode.countDown();
            until(() -> f.releases == 1, "cancelled worker releases exactly once");
            check(f.received.size() == 1 && f.finished == 0, "queued frames and finalize skipped");
            runEvents(events);
            check(words.isEmpty() && d.text().isEmpty(), "no partial action text after cancellation");
        }
    }

    private static void deadlineAndSharedLock() throws Exception {
        Fake f = new Fake(true);
        StreamingDecoder d = new StreamingDecoder(f, () -> true, Runnable::run, ignored -> { });
        d.offer(new float[]{1});
        check(f.insideDecode.await(1, TimeUnit.SECONDS), "first session owns decode lock");
        check(!d.await(5), "deadline cancels worker");
        check(d.error() != null && d.error().contains("too long"), "timeout error distinct");
        Fake second = new Fake(false);
        StreamingDecoder d2 = new StreamingDecoder(second, () -> true, Runnable::run, ignored -> { });
        d2.offer(new float[]{2});
        until(() -> second.received.size() == 1, "second session accepted input");
        check(second.decodes == 0, "new session cannot overlap old native inference");
        d2.cancel();
        f.allowDecode.countDown();
        until(() -> f.releases == 1 && second.releases == 1, "both cancelled streams released");
        check(second.decodes == 0, "cancellation checked after acquiring shared decode lock");
    }

    public static void main(String[] args) throws Exception {
        slowDecoderAndFinalization();
        resetDuringDecode();
        stalePostedCallback();
        cancellationAndOverflow();
        deadlineAndSharedLock();
        System.out.println("PASS StreamingDecoder concurrency scenarios; checks=" + checks);
    }
}
