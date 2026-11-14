package com.cleanroommc.unified;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The official launcher still runs 1.12.2 on Java 8u51, whose root certificates predate the CAs that GitHub and
 * Adoptium use today. This adds the root certificates of the JDK that built the jar ({@code cacerts.pem}) next to the
 * JVM's own, for HTTPS from this (short-lived, about-to-exit) process only. Nothing is ever trusted less than before.
 */
final class ModernTrust {

    private static boolean installed;

    private ModernTrust() {
    }

    static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        try (InputStream in = ModernTrust.class.getClassLoader().getResourceAsStream(BundledLibraries.ROOT + "cacerts.pem")) {
            if (in == null) {
                return;
            }
            List<X509Certificate> roots = parse(readAll(in));
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            for (int i = 0; i < roots.size(); i++) {
                store.setCertificateEntry("bundled-" + i, roots.get(i));
            }
            X509TrustManager bundled = trustManager(store);
            X509TrustManager system;
            try {
                system = trustManager(null);
            } catch (GeneralSecurityException | RuntimeException e) {
                system = null; // broken or empty JVM trust store
            }

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{system == null ? bundled : new EitherTrustManager(system, bundled)}, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            UnifiedLog.error("Could not add bundled root certificates; downloads use the JVM's own", e);
        }
    }

    private static List<X509Certificate> parse(String pem) {
        List<X509Certificate> certs = new ArrayList<>();
        CertificateFactory factory;
        try {
            factory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            return certs;
        }
        String begin = "-----BEGIN CERTIFICATE-----";
        String end = "-----END CERTIFICATE-----";
        int pos = 0;
        while ((pos = pem.indexOf(begin, pos)) >= 0) {
            int stop = pem.indexOf(end, pos);
            if (stop < 0) {
                break;
            }
            String body = pem.substring(pos + begin.length(), stop).replaceAll("\\s", "");
            pos = stop + end.length();
            try {
                certs.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(body))));
            } catch (CertificateException | IllegalArgumentException ignored) {
                // An algorithm this old JVM does not know; skip that root
            }
        }
        return certs;
    }

    private static X509TrustManager trustManager(KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager tm : factory.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new GeneralSecurityException("No X509TrustManager available");
    }

    private static String readAll(InputStream in) throws IOException {
        byte[] buf = new byte[1 << 16];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.US_ASCII);
    }

    /** Trusts a server when either the JVM's own roots or the bundled roots do. */
    private static final class EitherTrustManager implements X509TrustManager {
        private final X509TrustManager first;
        private final X509TrustManager second;

        EitherTrustManager(X509TrustManager first, X509TrustManager second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            first.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                first.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                second.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] a = first.getAcceptedIssuers();
            X509Certificate[] b = second.getAcceptedIssuers();
            X509Certificate[] all = new X509Certificate[a.length + b.length];
            System.arraycopy(a, 0, all, 0, a.length);
            System.arraycopy(b, 0, all, a.length, b.length);
            return all;
        }
    }
}
