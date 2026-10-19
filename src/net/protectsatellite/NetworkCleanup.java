// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** TLS close/disconnect can write to the network, even when only cancelling. */
final class NetworkCleanup {
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "protect-network-cleanup"); t.setDaemon(true); return t;
    });
    interface Action { void run() throws Exception; }
    static final class Task implements AutoCloseable {
        private final Action action;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final CountDownLatch finished = new CountDownLatch(1);
        Task(Action action) { this.action = action; }
        /** Safe from UI callbacks; never executes the action on its caller. */
        public void close() { if (claimed.compareAndSet(false, true)) WORKERS.execute(this::perform); }
        /** Only for a resource's network-owner thread, e.g. HTTP request finally. */
        void closeOnOwnerThread() { if (claimed.compareAndSet(false, true)) perform(); }
        private void perform() {
            try { action.run(); }
            catch (Exception ignored) { /* Best-effort cancellation; never log secret-bearing exceptions. */ }
            finally { finished.countDown(); }
        }
        boolean await(long timeout, TimeUnit unit) throws InterruptedException { return finished.await(timeout, unit); }
    }
    private NetworkCleanup() {}
}
