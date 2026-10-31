package org.rpgengine.inference.admission;

/** The kinds of local model that compete for the one GPU and RAM pool. */
public enum Modality {
    /** A text model behind a chat or completion endpoint. */
    TEXT,
    /** An image diffusion model. */
    IMAGE,
    /** A speech synthesis or recognition model. */
    SPEECH
}
