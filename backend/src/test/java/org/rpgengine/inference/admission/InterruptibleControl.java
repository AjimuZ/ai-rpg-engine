package org.rpgengine.inference.admission;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.rpgengine.inference.ModelControlException;
import org.rpgengine.inference.ModelServerControl;

/**
 * A control that treats interruption the way the real HTTP one does: a call on an interrupted thread fails with
 * {@code control-interrupted} and leaves the flag set. A load or an unload can also be held open, to stand for a slow
 * server call under the executor's monitor. It shows nothing about a real server.
 */
final class InterruptibleControl implements ModelServerControl {
    private final ModelServerControl server;
    private volatile CountDownLatch loadGate = new CountDownLatch(0);
    private volatile CountDownLatch unloadGate = new CountDownLatch(0);
    private volatile boolean reachedLoad;
    private volatile boolean reachedUnload;

    InterruptibleControl(ModelServerControl server) {
        this.server = server;
    }

    /** Hold every later load until {@link #open}. */
    void holdLoads() {
        loadGate = new CountDownLatch(1);
    }

    /** Hold every later unload until {@link #open}. */
    void holdUnloads() {
        unloadGate = new CountDownLatch(1);
    }

    void open() {
        loadGate.countDown();
        unloadGate.countDown();
    }

    /** @return true once a load has been reached */
    boolean reachedLoad() {
        return reachedLoad;
    }

    /** @return true once an unload has been reached */
    boolean reachedUnload() {
        return reachedUnload;
    }

    private static void check() throws ModelControlException {
        if (Thread.currentThread().isInterrupted()) {
            throw new ModelControlException("control-interrupted");
        }
    }

    private static void pass(CountDownLatch gate) throws ModelControlException {
        try {
            gate.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ModelControlException("control-interrupted");
        }
    }

    @Override
    public void load(String model) throws ModelControlException {
        check();
        reachedLoad = true;
        pass(loadGate);
        server.load(model);
    }

    @Override
    public void unload(String model) throws ModelControlException {
        check();
        reachedUnload = true;
        pass(unloadGate);
        server.unload(model);
    }

    @Override
    public Residency status(String model) throws ModelControlException {
        check();
        return server.status(model);
    }

    @Override
    public List<String> resident() throws ModelControlException {
        check();
        return server.resident();
    }

    @Override
    public void saveSlot(String model, int slot, String file) throws ModelControlException {
        check();
        server.saveSlot(model, slot, file);
    }

    @Override
    public void restoreSlot(String model, int slot, String file) throws ModelControlException {
        check();
        server.restoreSlot(model, slot, file);
    }
}
