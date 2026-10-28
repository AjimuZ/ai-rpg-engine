package org.rpgengine.inference.contract;

/**
 * Thrown when a rendered context already contains a {@link DisclosureGuard} marker: the caller
 * assembled input outside the task's public knowledge scope, so nothing may be submitted. The
 * message never contains the marker or the context.
 */
public final class ContextScopeException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /** Create the fixed, content-free failure. */
    ContextScopeException() {
        super("context contains a privileged marker");
    }
}
