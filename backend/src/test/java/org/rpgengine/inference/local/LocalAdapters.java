package org.rpgengine.inference.local;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.ProfileResolver;
import org.rpgengine.inference.RuntimeStatus;
import org.rpgengine.inference.TaskHandle;
import org.rpgengine.inference.TaskRequest;
import org.springframework.mock.env.MockEnvironment;

/**
 * Shared construction of a started local adapter for suites in other packages, which cannot name the
 * package-private adapter. Stopping it through {@link Running#close()} ends its dispatcher.
 */
public final class LocalAdapters {
    private LocalAdapters() {
    }

    /** A started adapter behind the neutral port. */
    public static final class Running implements NarrationTaskPort, AutoCloseable {
        private final LocalTaskAdapter adapter;

        private Running(LocalTaskAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public TaskHandle submit(TaskRequest request) {
            return adapter.submit(request);
        }

        @Override
        public void retireSession(UUID sessionId) {
            adapter.retireSession(sessionId);
        }

        @Override
        public RuntimeStatus status() {
            return adapter.status();
        }

        @Override
        public void close() {
            adapter.stop();
        }
    }

    /**
     * Start an adapter and wait until it is READY.
     *
     * @param server the scripted server
     * @param profiles resolves each task's slot
     * @return the running adapter
     * @throws InterruptedException when interrupted while waiting
     * @throws IllegalStateException when the adapter is not READY within five seconds
     */
    public static Running start(ScriptedModelServer server, ProfileResolver profiles) throws InterruptedException {
        var settings = LocalSettings.from(new MockEnvironment().withProperty(LocalSettings.PREFIX + "url", server.url()));
        var adapter = new LocalTaskAdapter(settings, profiles);
        adapter.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (adapter.status().state() != RuntimeStatus.State.READY) {
            if (System.nanoTime() - deadline >= 0) {
                adapter.stop();
                throw new IllegalStateException("adapter not ready: " + adapter.status().reasonCode());
            }
            Thread.sleep(5);
        }
        return new Running(adapter);
    }
}
