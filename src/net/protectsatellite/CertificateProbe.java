// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.io.IOException;
import java.net.*;
import java.security.MessageDigest;
import java.security.cert.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;

/** TLS handshake only. Never sends HTTP, API credentials, audio or video. */
final class CertificateProbe implements AutoCloseable {
    static final class Offer {
        final String origin, fingerprint, details;
        Offer(String origin, X509Certificate certificate) throws Exception {
            this.origin = origin;
            fingerprint = SecureTransport.hex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
            details = "Console: " + origin + "\nSubject: " + DisplayText.singleLine(certificate.getSubjectX500Principal().getName(), 180)
                + "\nIssuer: " + DisplayText.singleLine(certificate.getIssuerX500Principal().getName(), 180)
                + "\nValid from: " + certificate.getNotBefore() + "\nValid until: " + certificate.getNotAfter()
                + "\nSHA-256: " + fingerprint
                + "\n\nThis self-signed certificate is not trusted by Android. Verify its fingerprint independently before accepting. Acceptance pins this exact certificate to this console; a changed certificate will be rejected.";
        }
    }
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile NetworkCleanup.Task active;
    Offer check(Settings settings) throws Exception {
        try {
            SecureTransport tls = new SecureTransport(settings);
            handshake(settings, tls.factory, settings.certificatePin.isEmpty());
            return null;
        } catch (SSLException e) {
            // A changed saved pin must not turn into a new acceptance offer.
            if (!settings.certificatePin.isEmpty() || closed.get()) throw e;
        }
        final X509Certificate[][] seen = new X509Certificate[1][];
        SSLContext inspect = SSLContext.getInstance("TLS");
        inspect.init(null, new TrustManager[] {new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException("Client TLS not supported"); }
            public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                // Only collect for display, on a connection that cannot send an API key.
                if (chain == null || chain.length != 1) throw new CertificateException("Not a self-signed leaf certificate");
                seen[0] = chain.clone();
            }
        }}, null);
        handshake(settings, inspect.getSocketFactory(), false);
        if (closed.get()) throw new IOException("Setup cancelled");
        if (seen[0] == null) throw new SSLHandshakeException("No self-signed certificate presented");
        X509Certificate cert = seen[0][0];
        cert.checkValidity();
        if (!cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal()))
            throw new SSLHandshakeException("Certificate is not self-signed");
        cert.verify(cert.getPublicKey());
        return new Offer(settings.console.toString(), cert);
    }
    private void handshake(Settings settings, SSLSocketFactory factory, boolean verifyName) throws Exception {
        if (closed.get()) throw new IOException("Setup cancelled");
        Socket raw = new Socket();
        NetworkCleanup.Task rawCleanup = new NetworkCleanup.Task(raw::close); active = rawCleanup;
        try {
            if (closed.get()) throw new IOException("Setup cancelled");
            int port = settings.console.getPort() < 0 ? 443 : settings.console.getPort();
            raw.connect(new InetSocketAddress(settings.console.getHost(), port), 8000);
            SSLSocket socket = (SSLSocket) factory.createSocket(raw, settings.console.getHost(), port, true);
            NetworkCleanup.Task cleanup = new NetworkCleanup.Task(socket::close); active = cleanup;
            try {
                if (closed.get()) throw new IOException("Setup cancelled");
                socket.setSoTimeout(8000);
                SSLParameters parameters = socket.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm(verifyName ? "HTTPS" : null);
                socket.setSSLParameters(parameters); socket.startHandshake();
            } finally { cleanup.closeOnOwnerThread(); }
        } finally { rawCleanup.closeOnOwnerThread(); active = null; }
    }
    public void close() { closed.set(true); NetworkCleanup.Task task = active; if (task != null) task.close(); }
}
