package com.example.aroma;

import android.content.Context;
import android.util.Log;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Generates and persists a self-signed RSA TLS certificate for the LocalSend HTTPS server.
 * The same cert is reused across restarts to keep the fingerprint stable.
 */
public class LocalSendCertHelper {

    private static final String TAG = "LocalSendCert";

    static {
        // Ensure BouncyCastle provider is registered for cert generation
        if (java.security.Security.getProvider("BC") == null) {
            java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        }
    }

    private static final String KS_FILE = "localsend_tls.p12";
    private static final String KS_ALIAS = "localsend";
    private static final char[] KS_PASS = "aroma_ls".toCharArray();

    private final Context context;
    private SSLContext sslContext;
    private String fingerprint;

    public LocalSendCertHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Returns cached or newly generated SSLContext. */
    public SSLContext getSSLContext() throws Exception {
        if (sslContext != null) return sslContext;
        init();
        return sslContext;
    }

    /** Returns SHA-256 fingerprint of the cert as a lowercase hex string. */
    public String getFingerprint() throws Exception {
        if (fingerprint != null) return fingerprint;
        init();
        return fingerprint;
    }

    private synchronized void init() throws Exception {
        File ksFile = new File(context.getFilesDir(), KS_FILE);
        KeyStore ks = KeyStore.getInstance("PKCS12");

        if (ksFile.exists()) {
            try (FileInputStream fis = new FileInputStream(ksFile)) {
                ks.load(fis, KS_PASS);
                Log.d(TAG, "Loaded existing TLS keystore");
            }
        } else {
            Log.d(TAG, "Generating new TLS cert...");
            ks = generateKeyStore();
            try (FileOutputStream fos = new FileOutputStream(ksFile)) {
                ks.store(fos, KS_PASS);
            }
            Log.d(TAG, "TLS cert saved");
        }

        X509Certificate cert = (X509Certificate) ks.getCertificate(KS_ALIAS);
        fingerprint = computeFingerprint(cert);
        Log.d(TAG, "Fingerprint: " + fingerprint);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, KS_PASS);

        sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
    }

    private KeyStore generateKeyStore() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        Calendar cal = Calendar.getInstance();
        Date notBefore = cal.getTime();
        cal.add(Calendar.YEAR, 10);
        Date notAfter = cal.getTime();

        X500Name subject = new X500Name("CN=AROMA-LocalSend");
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(SecureRandom.getInstance("SHA1PRNG").nextLong()),
                notBefore,
                notAfter,
                subject,
                kp.getPublic()
        );
        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(certBuilder.build(signer));

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(KS_ALIAS, kp.getPrivate(), KS_PASS, new X509Certificate[]{cert});
        return ks;
    }

    private static String computeFingerprint(X509Certificate cert) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] der = cert.getEncoded();
        byte[] digest = md.digest(der);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    /** Returns a trust-all SSLContext for outbound HTTPS calls to peers with self-signed certs. */
    public static SSLContext buildTrustAllContext() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
            @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
        }}, null);
        return ctx;
    }

    /** Deletes the stored keystore, forcing a new cert on next start. */
    public void reset() {
        new File(context.getFilesDir(), KS_FILE).delete();
        sslContext = null;
        fingerprint = null;
    }
}
