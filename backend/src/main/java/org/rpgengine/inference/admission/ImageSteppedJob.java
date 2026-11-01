package org.rpgengine.inference.admission;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.rpgengine.inference.ImageHandle;
import org.rpgengine.inference.ImageResult;

/**
 * A batch of images as a {@link SteppedJob}: it runs them one after another on their adapters. The image port has no
 * per-step API, so a step is one bounded wait on the current handle, not one diffusion step, and what the executor
 * and a caller's cancel can do between two steps is ask the backend to stop.
 *
 * <p>The current job's handle is cancelled at most once: at the step after a caller asked, or in {@link #abandoned},
 * which waits a bounded time for the backend so that the model is not unloaded under running work. An interrupt does
 * not shorten that wait. A job that outlasts it is left to the backend, and the model is unloaded anyway. A job that
 * finished before the cancel reached it keeps its answer. Whatever this job did not finish is for
 * {@link LeasedImageAdmission} to end from the batch's outcome.
 *
 * <p>All but {@link #add}, {@link #images} and {@link #stop} is confined to the thread that runs the batch.
 */
final class ImageSteppedJob implements SteppedJob {
    private final Duration stepWait;
    private final Duration stopWait;
    private final Deque<AdmittedImage> queued = new ArrayDeque<>();
    private final List<AdmittedImage> everyone = new ArrayList<>();
    private AdmittedImage current;
    private ImageHandle handle;
    private CompletableFuture<ImageResult> answer;
    private boolean cancelSent;
    private boolean cancelAcknowledged;
    private volatile boolean stopping;

    /**
     * @param images the batch at its start
     * @param stepWait longest wait of one step, which bounds how late a stop takes effect; positive
     * @param stopWait longest wait in {@link #abandoned} for a cancelled job to end
     */
    ImageSteppedJob(Collection<AdmittedImage> images, Duration stepWait, Duration stopWait) {
        this.stepWait = stepWait;
        this.stopWait = stopWait;
        queued.addAll(images);
        everyone.addAll(images);
    }

    /** A request joined the batch's lease. If the last step has already passed, the batch's outcome ends it. */
    synchronized void add(AdmittedImage image) {
        queued.add(image);
        everyone.add(image);
    }

    /** @return every image this batch ever held */
    synchronized List<AdmittedImage> images() {
        return List.copyOf(everyone);
    }

    /**
     * End the batch at its next step, which fails, so that {@link #abandoned} and the unload follow as for any stopped
     * job. This is how a closing path stops a batch: unlike an interrupt, it cannot reach a server call in progress and
     * cost the unload its confirmation.
     */
    void stop() {
        stopping = true;
    }

    private synchronized AdmittedImage next() {
        return queued.poll();
    }

    private synchronized boolean moreQueued() {
        return !queued.isEmpty();
    }

    /**
     * @return true while an image runs or waits
     * @throws InterruptedException when the thread is interrupted during the wait
     * @throws Exception when {@link #stop} was called, or an adapter breaks its contract by throwing or by failing its
     *         result stage
     */
    @Override
    public boolean step() throws Exception {
        if (stopping) {
            throw new CancellationException("closing");
        }
        if (current == null && !startNext()) {
            return false;
        }
        if (current.cancelAsked()) {
            sendCancel();
        }
        ImageResult outcome;
        try {
            outcome = answer.get(stepWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException stillRunning) {
            return true;
        }
        current.finish(outcome);
        current = null;
        handle = null;
        answer = null;
        return moreQueued();
    }

    private boolean startNext() {
        for (AdmittedImage image = next(); image != null; image = next()) {
            if (image.startRunning()) {
                current = image;
                cancelSent = false;
                cancelAcknowledged = false;
                handle = image.adapter().submit(image.request());
                answer = handle.result().toCompletableFuture();
                return true;
            }
        }
        return false;
    }

    private void sendCancel() {
        if (!cancelSent) {
            cancelSent = true;
            cancelAcknowledged = handle.cancel();
        }
    }

    @Override
    public void abandoned() {
        if (handle == null) {
            return;
        }
        sendCancel();
        if (answer == null) {
            return;
        }
        ImageResult last = lastAnswer();
        if (last != null && !cancelAcknowledged) {
            current.finish(last);
        }
    }

    /** @return the backend's answer within the stop wait, or null without one; a pending interrupt is set again */
    private ImageResult lastAnswer() {
        long deadline = System.nanoTime() + stopWait.toNanos();
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return answer.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                } catch (InterruptedException again) {
                    interrupted = true;
                } catch (TimeoutException | ExecutionException | CancellationException noAnswer) {
                    // The batch's outcome ends this job, and the backend is left to end its own work.
                    return null;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
