package org.rpgengine.inference.admission;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import org.rpgengine.inference.ImageGenerationPort;
import org.rpgengine.inference.ImageHandle;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.ImageResult;
import org.rpgengine.inference.scheduling.WorkClass;

/**
 * How an image job reaches its adapter (P21.4-C.1.2.2.2, docs/34 section 10). A bean of this type exists only when
 * {@code engine.inference.admission.enabled} is {@code true}, and then the job waits for a lease and runs as a
 * stepped job. A caller without the bean uses {@link #direct()}:
 * {@code provider.getIfAvailable(ImageAdmission::direct)}.
 *
 * <p>The result keeps the contract of {@link ImageHandle}. An admission that cannot serve the job ends it
 * {@link ImageResult.Status#FAILED} with one of the fixed codes below or {@code image-shutdown}, and the caller
 * answers every failure the same way: with its catalog art ({@link #usesCatalogArt}). Nothing retries by itself, so
 * a caller that still wants the image submits again.
 */
public interface ImageAdmission {
    /** Interactive text is queued or expected, or the wait ended before a lease was granted. */
    String DEFERRED = "image-admission-deferred";
    /** Too little free GPU memory to load the model when the caller's wait ended. */
    String DELAYED = "image-admission-delayed";
    /** A text model could not be removed to make room when the caller's wait ended. */
    String SWAP_FAILED = "image-admission-swap-failed";
    /** Free GPU memory stayed too low for longer than the owner's patience. */
    String CONTENTION = "image-admission-contention";
    /** Free GPU memory fell under the floor while the job ran. */
    String YIELDED = "image-admission-yielded";
    /** The job gave way between two steps to more urgent work. */
    String INTERRUPTED = "image-admission-interrupted";
    /** The job used up the lease's time cap. */
    String TIME_CAPPED = "image-admission-time-capped";
    /** The admission lapsed or ended before this job could finish. */
    String LOST = "image-admission-lost";
    /** A step failed, or the thread running the batch was interrupted. */
    String JOB_FAILED = "image-admission-job-failed";
    /** The model would not load, or no control can load it. */
    String LOAD_FAILED = "image-admission-load-failed";
    /** The request could not be accepted, for example because it exceeds the whole budget. */
    String UNAVAILABLE = "image-admission-unavailable";

    /**
     * What one job asks of the admission, beside the request every backend receives.
     *
     * @param workClass priority class
     * @param owner fairness owner, normally the campaign the image belongs to
     * @param maxWait how long the job may wait for its model before the caller gets its fallback; zero means that the
     *        first answer other than a start ends it, and a wait above the owner's patience lets contention end it
     *        as {@link #CONTENTION}
     */
    record Ticket(WorkClass workClass, UUID owner, Duration maxWait) {
        /** Require every component; a negative wait fails. */
        public Ticket {
            Objects.requireNonNull(workClass, "workClass");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(maxWait, "maxWait");
            if (maxWait.isNegative()) {
                throw new IllegalArgumentException("maxWait is negative");
            }
        }
    }

    /**
     * Start one job. Never waits for a model server or the adapter's backend, and never throws for a backend or
     * admission condition. With a bean it may wait briefly for the coordinator, which is never held across a model
     * server call.
     *
     * @param adapter the port that generates the image
     * @param request what to generate
     * @param ticket priority, owner and wait
     * @return the handle of the job
     */
    ImageHandle submit(ImageGenerationPort adapter, ImageRequest request, Ticket ticket);

    /** @return the behaviour without the switch: the adapter is called at once and the ticket is ignored */
    static ImageAdmission direct() {
        return (adapter, request, ticket) -> adapter.submit(request);
    }

    /**
     * @param result a terminal result
     * @return true when the caller shows catalog art: any failure, whichever side reported it. A completed job has
     *         its image, and a cancelled one was given up by the caller.
     */
    static boolean usesCatalogArt(ImageResult result) {
        return result.status() == ImageResult.Status.FAILED;
    }
}
