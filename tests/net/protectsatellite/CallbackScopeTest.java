// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class CallbackScopeTest {
    static void run() throws Exception {
        CallbackScope scope = new CallbackScope(); AtomicInteger callbacks = new AtomicInteger();
        long old = scope.next();
        CoreTest.check(scope.current(old), "New playback owns its callbacks");
        scope.run(old, callbacks::incrementAndGet);
        long replacement = scope.next();
        CoreTest.check(!scope.current(old) && scope.current(replacement), "Replacing playback revokes all old callback tokens");
        scope.run(old, callbacks::incrementAndGet);
        CoreTest.check(callbacks.get() == 1, "An old failure cannot act on a replacement playback session");
        scope.run(replacement, callbacks::incrementAndGet);
        scope.next(); scope.run(replacement, callbacks::incrementAndGet);
        CoreTest.check(callbacks.get() == 2, "Close revokes queued metadata and microphone-stop callbacks");
        CountDownLatch queued = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            long pending = scope.next();
            Future<?> callback = worker.submit(() -> {
                queued.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Callback not released"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                scope.run(pending, callbacks::incrementAndGet);
            });
            CoreTest.check(queued.await(3, TimeUnit.SECONDS), "Playback callback can be queued before closure");
            scope.next(); release.countDown(); callback.get(3, TimeUnit.SECONDS);
            CoreTest.check(callbacks.get() == 2, "Callback must recheck ownership at execution time, not enqueue time");
        } finally { release.countDown(); worker.shutdownNow(); }
    }
}
