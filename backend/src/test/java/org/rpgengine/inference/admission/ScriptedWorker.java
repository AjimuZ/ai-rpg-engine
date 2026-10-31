package org.rpgengine.inference.admission;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.rpgengine.inference.ModelControlException;
import org.rpgengine.inference.ModelServerControl;

/**
 * In-memory stand-in for an image or speech worker, which does not exist yet: it holds models in a set,
 * reports each call to a listener and fails on request. It shows nothing about a real worker.
 */
final class ScriptedWorker implements ModelServerControl {
    private final List<String> resident = new ArrayList<>();
    private final Consumer<String> events;
    int loadFailures;
    int unloadFailures;

    ScriptedWorker(Consumer<String> events) {
        this.events = events;
    }

    @Override
    public void load(String model) throws ModelControlException {
        events.accept("worker load " + model);
        if (loadFailures > 0) {
            loadFailures--;
            throw new ModelControlException("control-status");
        }
        resident.add(model);
    }

    @Override
    public void unload(String model) throws ModelControlException {
        events.accept("worker unload " + model);
        if (unloadFailures > 0) {
            unloadFailures--;
            throw new ModelControlException("control-status");
        }
        resident.remove(model);
    }

    @Override
    public Residency status(String model) {
        return resident.contains(model) ? Residency.LOADED : Residency.GONE;
    }

    @Override
    public List<String> resident() {
        return List.copyOf(resident);
    }

    @Override
    public void saveSlot(String model, int slot, String file) throws ModelControlException {
        throw new ModelControlException("control-status");
    }

    @Override
    public void restoreSlot(String model, int slot, String file) throws ModelControlException {
        throw new ModelControlException("control-status");
    }

    /** Make a model resident as an earlier process could have left it. */
    void leaves(String model) {
        resident.add(model);
    }

    boolean holds(String model) {
        return resident.contains(model);
    }
}
