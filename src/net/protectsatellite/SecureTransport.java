// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.*;

final class SecureTransport {
    private final Settings settings;
    final SSLSocketFactory factory;
    SecureTransport(Settings settings) throws Exception {
        this.settings = settings;
        if (settings.certificatePin.isEmpty()) factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        else {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException("Client TLS not supported"); }
                public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    try {
                        if (chain == null || chain.length == 0) throw new CertificateException("Missing server certificate");
                        chain[0].checkValidity();
                        byte[] digest = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                        if (!MessageDigest.isEqual(hex(digest).getBytes("US-ASCII"), settings.certificatePin.getBytes("US-ASCII")))
                            throw new CertificateException("Console certificate fingerprint mismatch");
                    } catch (CertificateException e) { throw e; }
                    catch (Exception e) { throw new CertificateException("Certificate validation failed"); }
                }
            }}, null);
            factory = context.getSocketFactory();
        }
    }
    void configure(HttpsURLConnection connection) {
        connection.setSSLSocketFactory(factory);
        // An explicit certificate pin is the independently supplied identity,
        // including consoles whose certificate SAN does not contain their LAN IP.
        if (!settings.certificatePin.isEmpty()) connection.setHostnameVerifier((host, session) -> host.equals(settings.console.getHost()));
    }
    Socket rtsp(URI uri) throws IOException {
        if (!uri.getHost().equalsIgnoreCase(settings.console.getHost())) throw new IOException("Unexpected streaming host");
        Socket raw = new Socket();
        try {
            int port = uri.getPort() > 0 ? uri.getPort() : 7441;
            raw.connect(new InetSocketAddress(uri.getHost(), port), 8000);
            // Match HA's UniFi Protect camera stream_options[verify_ssl] = False.
            // Protect's RTSPS listener uses a separate self-signed certificate.
            // This context is local to video: NEVER use it for API requests or
            // change the process-wide SSL defaults. No API key is sent on RTSPS.
            SSLContext streamContext;
            try {
                streamContext = SSLContext.getInstance("TLS");
                streamContext.init(null, new TrustManager[] {new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                        throw new CertificateException("Client TLS not supported");
                    }
                    public void checkServerTrusted(X509Certificate[] chain, String auth) { /* RTSPS video only: intentionally unverified. */ }
                }}, null);
            } catch (java.security.GeneralSecurityException e) { throw new IOException("Could not initialize video TLS"); }
            SSLSocket tls = (SSLSocket) streamContext.getSocketFactory().createSocket(raw, uri.getHost(), port, true);
            tls.setSoTimeout(8000);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm(null);
            tls.setSSLParameters(parameters);
            tls.startHandshake();
            return tls;
        } catch (IOException | RuntimeException e) { try { raw.close(); } catch (IOException ignored) {} throw e; }
    }
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte v : b) s.append(String.format(java.util.Locale.ROOT, "%02x", v & 255)); return s.toString(); }
}
