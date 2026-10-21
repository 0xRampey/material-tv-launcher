package com.rampey.voice;

import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Metadata only. No audio, transcript, thread names, arbitrary paths, or cross-process reads. */
final class AsrDiagnostics {
    static final int MAX_TASKS = 64;
    // Cooperative task-scan budget; individual bounded /proc reads cannot be interrupted.
    static final long SNAPSHOT_BUDGET_NS = 8_000_000;
    private static final AtomicLong NEXT = new AtomicLong();
    private static final Source ANDROID = new AndroidSource();
    private AsrDiagnostics() { }

    interface Source {
        long wallNs();
        long processCpuMs();
        long threadCpuNs();
        int tid();
        int nice(int tid);
        String read(int tid, String file, int limit);
        TaskIds tasks(int limit, long deadlineNs);
        void emit(String line);
    }
    static final class TaskIds {
        final List<Integer> ids;
        final boolean available, truncated;
        TaskIds(List<Integer> ids, boolean available, boolean truncated) {
            this.ids = ids; this.available = available; this.truncated = truncated;
        }
    }
    static final class Stamp {
        long wall, process, thread;
        int tid;
        Stamp(Source source) {
            wall = source.wallNs(); process = source.processCpuMs();
            thread = source.threadCpuNs(); tid = source.tid();
        }
    }
    static final class Task {
        long start, run, wait, slices;
        int nice;
        Task(long start, int nice, long[] sched) {
            this.start = start; this.nice = nice;
            run = sched[0]; wait = sched[1]; slices = sched[2];
        }
    }
    static final class Snapshot {
        int tid, nice = -100;
        long[] caller;
        long cost;
        String processGroup, callerGroup;
        boolean enabled, available, truncated, budget;
        final Map<Integer, Task> tasks = new LinkedHashMap<>();
    }
    static final class Span {
        final long id;
        final Source source;
        final Snapshot before;
        final Stamp start;
        Span(Source source, boolean sampleTasks) {
            id = NEXT.incrementAndGet(); this.source = source;
            before = snapshot(source, sampleTasks);
            // File reads happen before clocks; the native decode begins immediately after this returns.
            start = new Stamp(source);
        }
    }
    // Optional all-task sampling stays off for the physical trial; turn it on only in a focused diagnostic build.
    static Span begin() { return begin(ANDROID, false); }
    static Span begin(Source source, boolean sampleTasks) {
        try { return new Span(source, sampleTasks); }
        catch (RuntimeException | LinkageError unavailable) { return null; }
    }
    static void end(Span span, boolean completed) {
        if (span == null) return;
        try {
            // Stop clocks before collecting the second /proc snapshot and before logging.
            Stamp stop = new Stamp(span.source);
            Snapshot after = snapshot(span.source, span.before.enabled);
            span.source.emit(line(span, stop, after, completed));
        } catch (RuntimeException | LinkageError unavailable) {
            // Diagnostics must never replace a recognizer result/error or interfere with stream release.
        }
    }
    private static Snapshot snapshot(Source source, boolean sampleTasks) {
        long begin = source.wallNs();
        Snapshot out = new Snapshot(); out.tid = source.tid(); out.nice = source.nice(out.tid);
        out.caller = sched(source.read(out.tid, "schedstat", 256));
        out.processGroup = groups(source.read(0, "cgroup", 4096));
        out.callerGroup = groups(source.read(out.tid, "cgroup", 4096));
        out.enabled = sampleTasks;
        if (!sampleTasks) {
            out.cost = delta(source.wallNs(), begin);
            return out;
        }
        long deadline = begin + SNAPSHOT_BUDGET_NS;
        TaskIds listed = source.wallNs() >= deadline
                ? new TaskIds(new ArrayList<>(), false, true) : source.tasks(MAX_TASKS, deadline);
        out.available = listed.available; out.truncated = listed.truncated;
        int visited = 0;
        for (int tid : listed.ids) {
            if (++visited > MAX_TASKS) { out.truncated = true; break; }
            if (source.wallNs() - begin >= SNAPSHOT_BUDGET_NS) { out.budget = true; break; }
            if (tid <= 0 || tid == out.tid) continue;
            long[] identity = stat(source.read(tid, "stat", 2048), tid);
            long[] times = sched(source.read(tid, "schedstat", 256));
            if (identity != null && times != null)
                out.tasks.put(tid, new Task(identity[0], (int) identity[1], times));
        }
        out.cost = delta(source.wallNs(), begin);
        out.budget |= out.cost >= SNAPSHOT_BUDGET_NS;
        return out;
    }
    static long[] sched(String text) {
        if (text == null) return null;
        String[] fields = text.trim().split("\\s+");
        if (fields.length != 3) return null;
        try {
            long[] values = {Long.parseLong(fields[0]), Long.parseLong(fields[1]), Long.parseLong(fields[2])};
            for (long value : values) if (value < 0) return null;
            return values;
        } catch (NumberFormatException malformed) { return null; }
    }
    /** /proc stat comm may contain spaces/parentheses; never expose it in output. */
    static long[] stat(String text, int expectedTid) {
        if (text == null) return null;
        int open = text.indexOf('('), end = text.lastIndexOf(')');
        if (open < 1 || end <= open) return null;
        try {
            if (Integer.parseInt(text.substring(0, open).trim()) != expectedTid) return null;
            String[] fields = text.substring(end + 1).trim().split("\\s+");
            if (fields.length < 20 || fields[0].length() != 1) return null;
            long nice = Long.parseLong(fields[16]), start = Long.parseLong(fields[19]);
            return nice < -20 || nice > 19 || start < 0 ? null : new long[]{start, nice};
        } catch (NumberFormatException malformed) { return null; }
    }
    /** Only scheduling classes, never raw cgroup paths (which can contain uid/pid or app labels). */
    static String groups(String text) {
        if (text == null) return "unavailable";
        String cpu = "unavailable", cpuset = "unavailable", unified = "unavailable";
        for (String line : text.split("\n")) {
            String[] parts = line.split(":", 3);
            if (parts.length != 3 || !parts[0].matches("[0-9]+")) continue;
            String group = group(parts[2]);
            if (parts[1].isEmpty()) unified = group;
            for (String controller : parts[1].split(",")) {
                if (controller.equals("cpu")) cpu = group;
                if (controller.equals("cpuset")) cpuset = group;
            }
        }
        return "cpu:" + cpu + ",cpuset:" + cpuset + ",v2:" + unified;
    }
    private static String group(String path) {
        if (path.equals("/")) return "root";
        for (String segment : path.split("/"))
            if (segment.equals("top-app") || segment.equals("foreground") || segment.equals("background")
                    || segment.equals("system-background") || segment.equals("restricted")) return segment;
        return "other";
    }
    static long delta(long after, long before) { return before < 0 || after < before ? -1 : after - before; }
    private static long add(long a, long b) { return a < 0 || b < 0 || a > Long.MAX_VALUE - b ? -1 : a + b; }
    private static void field(StringBuilder out, String name, long value) { out.append(',').append('"').append(name).append("\":").append(value); }
    private static void text(StringBuilder out, String name, String value) { out.append(',').append('"').append(name).append("\":\"").append(value).append('"'); }
    private static String line(Span span, Stamp stop, Snapshot after, boolean completed) {
        Snapshot before = span.before;
        boolean same = span.start.tid == stop.tid;
        StringBuilder out = new StringBuilder("ASR_CPU {\"v\":1");
        field(out, "id", span.id); field(out, "decode_completed", completed ? 1 : 0);
        field(out, "wall_begin_ns", span.start.wall); field(out, "wall_end_ns", stop.wall);
        field(out, "wall_ns", delta(stop.wall, span.start.wall));
        field(out, "process_cpu_begin_ms", span.start.process); field(out, "process_cpu_end_ms", stop.process);
        field(out, "process_cpu_ms", delta(stop.process, span.start.process));
        field(out, "caller_cpu_begin_ns", span.start.thread); field(out, "caller_cpu_end_ns", stop.thread);
        field(out, "caller_cpu_ns", same ? delta(stop.thread, span.start.thread) : -1);
        field(out, "caller_tid_begin", span.start.tid); field(out, "caller_tid_end", stop.tid);
        field(out, "caller_nice_begin", before.nice); field(out, "caller_nice_end", after.nice);
        for (int i = 0; i < 3; i++) field(out, new String[]{"caller_run_ns", "caller_wait_ns", "caller_slices"}[i],
                same && before.caller != null && after.caller != null ? delta(after.caller[i], before.caller[i]) : -1);
        text(out, "process_cgroup_begin", before.processGroup); text(out, "process_cgroup_end", after.processGroup);
        text(out, "caller_cgroup_begin", before.callerGroup); text(out, "caller_cgroup_end", after.callerGroup);
        field(out, "snapshot_begin_ns", before.cost); field(out, "snapshot_end_ns", after.cost);
        field(out, "task_sampling_enabled", before.enabled ? 1 : 0);
        field(out, "tasks_begin_readable", before.available ? 1 : 0); field(out, "tasks_end_readable", after.available ? 1 : 0);
        field(out, "tasks_begin_sampled", before.tasks.size()); field(out, "tasks_end_sampled", after.tasks.size());
        field(out, "tasks_begin_truncated", before.truncated || before.budget ? 1 : 0);
        field(out, "tasks_end_truncated", after.truncated || after.budget ? 1 : 0);
        long run = 0, wait = 0, slices = 0; int matched = 0, born = 0, gone = 0;
        List<long[]> active = new ArrayList<>();
        for (Map.Entry<Integer, Task> entry : after.tasks.entrySet()) {
            Task a = before.tasks.get(entry.getKey()), b = entry.getValue();
            if (a == null || a.start != b.start) { born++; continue; }
            long r = delta(b.run, a.run), w = delta(b.wait, a.wait), s = delta(b.slices, a.slices);
            if (r < 0 || w < 0 || s < 0) continue;
            matched++; run = add(run, r); wait = add(wait, w); slices = add(slices, s);
            if (r > 0) active.add(new long[]{entry.getKey(), r, w, s, a.nice, b.nice});
        }
        for (Map.Entry<Integer, Task> entry : before.tasks.entrySet()) {
            Task b = after.tasks.get(entry.getKey());
            if (b == null || b.start != entry.getValue().start) gone++;
        }
        field(out, "other_tasks_matched", matched); field(out, "other_tasks_new_sample", born); field(out, "other_tasks_gone_sample", gone);
        field(out, "other_tasks_run_ns", matched == 0 ? -1 : run);
        field(out, "other_tasks_wait_ns", matched == 0 ? -1 : wait);
        field(out, "other_tasks_slices", matched == 0 ? -1 : slices);
        active.sort(Comparator.comparingLong((long[] t) -> t[1]).reversed());
        out.append(",\"top_other_tasks\":[");
        for (int i = 0; i < Math.min(4, active.size()); i++) {
            long[] t = active.get(i); if (i > 0) out.append(',');
            out.append("{\"tid\":").append(t[0]); field(out, "run_ns", t[1]); field(out, "wait_ns", t[2]);
            field(out, "slices", t[3]); field(out, "nice_begin", t[4]); field(out, "nice_end", t[5]); out.append('}');
        }
        return out.append("]}").toString();
    }
    private static final class AndroidSource implements Source {
        public long wallNs() { return SystemClock.elapsedRealtimeNanos(); }
        public long processCpuMs() { return Process.getElapsedCpuTime(); }
        public long threadCpuNs() { return Debug.threadCpuTimeNanos(); }
        public int tid() { return Process.myTid(); }
        public int nice(int tid) {
            try { return Process.getThreadPriority(tid); }
            catch (RuntimeException unavailable) { return -100; }
        }
        public String read(int tid, String file, int limit) {
            String base = tid == 0 ? "/proc/self/" : "/proc/self/task/" + tid + "/";
            try (FileInputStream input = new FileInputStream(base + file)) {
                byte[] data = new byte[limit + 1]; int length = 0, count;
                while (length < data.length && (count = input.read(data, length, data.length - length)) > 0) length += count;
                return length > limit ? null : new String(data, 0, length, StandardCharsets.US_ASCII);
            } catch (java.io.IOException | RuntimeException unavailable) { return null; }
        }
        public TaskIds tasks(int limit, long deadlineNs) {
            List<Integer> ids = new ArrayList<>(); boolean truncated = false;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get("/proc/self/task"))) {
                int visited = 0;
                for (Path path : stream) {
                    if (++visited > limit || SystemClock.elapsedRealtimeNanos() >= deadlineNs) {
                        truncated = true; break;
                    }
                    String id = path.getFileName().toString();
                    if (id.matches("[0-9]{1,10}")) {
                        try { int tid = Integer.parseInt(id); if (tid > 0) ids.add(tid); }
                        catch (NumberFormatException invalid) { /* Do not retain directory names. */ }
                    }
                }
                return new TaskIds(ids, true, truncated);
            } catch (java.io.IOException | RuntimeException unavailable) { return new TaskIds(ids, false, true); }
        }
        public void emit(String line) { android.util.Log.i(Voice.tag, line); }
    }
}
