package org.rpgengine.inference.admission;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import org.rpgengine.inference.ImageGenerationPort;
import org.rpgengine.inference.ImageHandle;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.ImageResult;

/**
 * One image owed to a caller, and the handle the caller holds through the wait for a lease and the run. Its result
 * completes once: the first {@link #finish} or cancel counts. A job not yet on its adapter is cancelled at once; a
 * running one is cancelled by its {@link ImageSteppedJob} at the next step. An acknowledged cancel never yields an
 * image or an error, whatever comes back.
 */
final class AdmittedImage implements ImageHandle {
    private final UUID id;
    private final ImageGenerationPort adapter;
    private final ImageRequest request;
    private final Instant giveUpAt;
    private final Consumer<AdmittedImage> withdraw;
    private final CompletableFuture<ImageResult> result = new CompletableFuture<>();
    private boolean running;
    private boolean cancelAsked;
    private volatile String waitingFor = ImageAdmission.DEFERRED;

    /**
     * @param id the identity of its admission request
     * @param adapter the port that generates the image
     * @param request what to generate
     * @param giveUpAt when its wait for a lease ends
     * @param withdraw called without waiting when a cancel finds the job not yet on its adapter; must not block
     */
    AdmittedImage(UUID id, ImageGenerationPort adapter, ImageRequest request, Instant giveUpAt,
            Consumer<AdmittedImage> withdraw) {
        this.id = id;
        this.adapter = adapter;
        this.request = request;
        this.giveUpAt = giveUpAt;
        this.withdraw = withdraw;
    }

    UUID id() {
        return id;
    }

    ImageGenerationPort adapter() {
        return adapter;
    }

    ImageRequest request() {
        return request;
    }

    Instant giveUpAt() {
        return giveUpAt;
    }

    /** @return the fixed code that says what the job is waiting for now */
    String waitingFor() {
        return waitingFor;
    }

    /** @param code the fixed code of the last reason the executor put this request back */
    void waitingFor(String code) {
        waitingFor = code;
    }

    @Override
    public CompletionStage<ImageResult> result() {
        return result.minimalCompletionStage();
    }

    @Override
    public boolean cancel() {
        synchronized (this) {
            if (result.isDone()) {
                return false;
            }
            cancelAsked = true;
            if (running) {
                return true;
            }
        }
        result.complete(ImageResult.cancelled());
        withdraw.accept(this);
        return true;
    }

    /** @return true when the job may go to its adapter; false when it has already ended */
    synchronized boolean startRunning() {
        if (result.isDone()) {
            return false;
        }
        running = true;
        return true;
    }

    synchronized boolean cancelAsked() {
        return cancelAsked;
    }

    /** End the job, unless it ended already. Callbacks run outside the lock, on the calling thread. */
    void finish(ImageResult outcome) {
        ImageResult last;
        synchronized (this) {
            last = cancelAsked ? ImageResult.cancelled() : outcome;
        }
        result.complete(last);
    }
}
