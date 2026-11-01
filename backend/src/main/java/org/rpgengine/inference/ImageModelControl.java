package org.rpgengine.inference;

import java.util.Objects;

/**
 * The control of whatever holds image checkpoints, as the admission wiring receives it. A wrapper rather than a second
 * {@link ModelServerControl} bean, so that the wiring's unique lookup of the text control never meets it.
 *
 * <p>Only a backend that can release its checkpoint and confirm that from its own answer may provide one, because the
 * lease executor frees capacity on that confirmation. The packaged image worker can. The {@code a1111-http} profile
 * cannot, so that backend never gets one and its batches end {@code LOAD_FAILED} (docs/34 section 10).
 *
 * @param control the control; its model names are lease models, including {@link #CURRENT_CHECKPOINT}
 */
public record ImageModelControl(ModelServerControl control) {
    /** The lease model of a request that names no checkpoint and takes the backend's current one. */
    public static final String CURRENT_CHECKPOINT = "image-current";

    /** Require the control. */
    public ImageModelControl {
        Objects.requireNonNull(control, "control");
    }
}
