// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

/** Invalidates callbacks when their playback session is replaced or closed. */
final class CallbackScope {
    private volatile long revision;
    synchronized long next() { return ++revision; }
    boolean current(long token) { return revision == token; }
    synchronized void run(long token, Runnable action) { if (current(token)) action.run(); }
}
