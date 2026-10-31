package org.rpgengine.inference;

import java.util.Objects;

/**
 * Terminal outcome of one image job. Exactly one of three shapes holds:
 * <ul>
 *   <li>{@link Status#COMPLETED}: a PNG that the adapter validated against the request, with its provenance;</li>
 *   <li>{@link Status#CANCELLED}: no image, because a cancel was acknowledged. Whatever the backend
 *       still produced is dropped;</li>
 *   <li>{@link Status#FAILED}: no image and a fixed lowercase error code. Backend text and addresses
 *       never reach the code.</li>
 * </ul>
 * The image bytes are untrusted until a runtime job accepts them; this record only promises that the
 * PNG is well formed and has the requested size.
 *
 * @param status terminal status
 * @param png validated PNG bytes, or null unless completed
 * @param provenance provenance of the image, or null unless completed
 * @param errorCode fixed error code, or null unless failed
 */
public record ImageResult(Status status, byte[] png, ImageProvenance provenance, String errorCode) {
    /** Terminal status of a job. */
    public enum Status {
        COMPLETED, CANCELLED, FAILED
    }

    public ImageResult {
        Objects.requireNonNull(status, "status");
        boolean completed = status == Status.COMPLETED;
        if (completed != (png != null) || completed != (provenance != null)
                || (status == Status.FAILED) != (errorCode != null)) {
            throw new IllegalArgumentException("image result fields do not match its status");
        }
        png = png == null ? null : png.clone();
    }

    /** @return a completed result; the bytes are copied */
    public static ImageResult completed(byte[] png, ImageProvenance provenance) {
        return new ImageResult(Status.COMPLETED, Objects.requireNonNull(png, "png"),
                Objects.requireNonNull(provenance, "provenance"), null);
    }

    /** @return a cancelled result */
    public static ImageResult cancelled() {
        return new ImageResult(Status.CANCELLED, null, null, null);
    }

    /** @return a failed result carrying a fixed code */
    public static ImageResult failed(String errorCode) {
        return new ImageResult(Status.FAILED, null, null, Objects.requireNonNull(errorCode, "errorCode"));
    }

    /** @return a copy of the PNG, or null unless completed */
    @Override
    public byte[] png() {
        return png == null ? null : png.clone();
    }
}
