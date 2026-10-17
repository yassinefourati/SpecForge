package io.specforge.jobs;

import io.specforge.source.SourceException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Long-running operations (D8): {@code start} returns at once with a job id; the UI polls {@link Job#view()} and may
 * cancel. Jobs run on daemon threads; the last {@value #KEEP} finished jobs are kept for polling.
 */
public class Jobs {

    private static final int KEEP = 20;

    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "specforge-job");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    /** The work of a job: reports progress, checks cancellation, returns the result shown to the UI. */
    @FunctionalInterface
    public interface Work {
        Object run(Job job) throws Exception;
    }

    public static final class Cancelled extends RuntimeException {
        public Cancelled() {
            super("Cancelled", null, false, false);
        }
    }

    public static final class Job {
        public final String id = UUID.randomUUID().toString();
        public final String kind;
        private volatile String state = "RUNNING";
        private volatile String phase = "";
        private volatile double progress;
        private volatile boolean cancelRequested;
        private volatile Object result;
        private volatile Map<String, Object> error;

        Job(String kind) {
            this.kind = kind;
        }

        public void phase(String phase, double progress) {
            this.phase = phase;
            this.progress = Math.max(0, Math.min(1, progress));
        }

        /** Throws when the user cancelled; call between units of work. */
        public void checkCancelled() {
            if (cancelRequested) throw new Cancelled();
        }

        public boolean finished() {
            return !"RUNNING".equals(state);
        }

        public Object result() {
            return result;
        }

        public Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("kind", kind);
            m.put("state", state);
            m.put("phase", phase);
            m.put("progress", progress);
            if (result != null) m.put("result", result);
            if (error != null) m.put("error", error);
            return m;
        }
    }

    public synchronized Job start(String kind, Work work) {
        Job job = new Job(kind);
        jobs.put(job.id, job);
        prune();
        pool.submit(() -> {
            try {
                job.result = work.run(job);
                job.phase("Done", 1);
                job.state = "DONE";
            } catch (Cancelled e) {
                job.state = "CANCELLED";
                job.error = error("JOB_CANCELLED", "Cancelled.", "", "");
            } catch (SourceException e) {
                job.state = "FAILED";
                job.error = error(e.code, e.getMessage(), e.hint, e.action);
            } catch (Throwable e) {
                job.state = "FAILED";
                // never echo raw exception text that could contain connection details; log the type only
                job.error = error("INTERNAL", "The operation failed (" + e.getClass().getSimpleName() + ").", "See the SpecForge console.", "");
                System.err.println("Job " + job.id + " (" + kind + ") failed: " + e.getClass().getName());
            }
        });
        return job;
    }

    public synchronized Job get(String id) {
        return jobs.get(id);
    }

    public synchronized boolean cancel(String id) {
        Job j = jobs.get(id);
        if (j == null || j.finished()) return false;
        j.cancelRequested = true;
        return true;
    }

    private void prune() {
        long finished = jobs.values().stream().filter(Job::finished).count();
        var it = jobs.values().iterator();
        while (finished > KEEP && it.hasNext()) {
            if (it.next().finished()) {
                it.remove();
                finished--;
            }
        }
    }

    public static Map<String, Object> error(String code, String message, String hint, String action) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        m.put("hint", hint);
        m.put("action", action);
        return m;
    }
}
