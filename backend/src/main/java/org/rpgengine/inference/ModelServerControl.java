package org.rpgengine.inference;

import java.util.List;

/**
 * What the admission package's lease executor may ask of whatever holds a model in memory: make it
 * resident, remove it, report it, and save or restore a slot's prompt cache. It offers no way to stop a
 * process, because the engine never terminates one.
 *
 * <p>A successful {@link #load} or {@link #unload} only means the request was accepted, since the server
 * may do the work afterwards; callers confirm the result through {@link #status}. Every method fails
 * with {@link ModelControlException} and its fixed code, never with server text.
 */
public interface ModelServerControl {
    /** Where a model stands, as far as the server says. */
    enum Residency {
        /** In memory and ready. */
        LOADED,
        /** On its way into memory. */
        LOADING,
        /** A state this engine does not name, such as sleeping; it may hold memory and is not ready. */
        OTHER,
        /** Not in memory, or not known to the server at all. */
        GONE,
        /** Its last load failed, so it is not in memory. */
        FAILED
    }

    /** Ask for {@code model} to become resident. */
    void load(String model) throws ModelControlException;

    /** Ask for {@code model} to leave memory. */
    void unload(String model) throws ModelControlException;

    /** @return where {@code model} stands now */
    Residency status(String model) throws ModelControlException;

    /** @return every model that may hold memory now, including those on their way in */
    List<String> resident() throws ModelControlException;

    /**
     * Save a slot's prompt cache of a resident model.
     *
     * @param model the resident model
     * @param slot server slot number, not negative
     * @param file file name inside the server's slot directory, chosen by the engine
     */
    void saveSlot(String model, int slot, String file) throws ModelControlException;

    /**
     * Restore a slot's prompt cache into a resident model.
     *
     * @param model the resident model
     * @param slot server slot number, not negative
     * @param file file name used to save it
     */
    void restoreSlot(String model, int slot, String file) throws ModelControlException;
}
