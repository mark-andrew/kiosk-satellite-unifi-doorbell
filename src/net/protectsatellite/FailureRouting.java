// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

/** Setup fixes connection/configuration failures, not Android decoder failures. */
final class FailureRouting {
    static boolean setup(Exception error, boolean connecting) {
        return connecting || error instanceof java.net.SocketException || error instanceof java.net.SocketTimeoutException
            || error instanceof java.net.UnknownHostException || error instanceof javax.net.ssl.SSLException || error instanceof java.io.EOFException;
    }
}
