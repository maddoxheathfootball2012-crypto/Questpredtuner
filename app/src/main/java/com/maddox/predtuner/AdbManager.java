package com.maddox.predtuner;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;

/**
 * Talks to the headset's own ADB daemon over wireless debugging, the same way
 * Quest Games Optimizer does. The key and certificate are made once and kept,
 * so pairing only has to happen once (until the app is uninstalled).
 */
public class AdbManager extends AbsAdbConnectionManager {

    private static AdbManager instance;

    private final PrivateKey privateKey;
    private final Certificate certificate;

    public static synchronized AdbManager get(Context context) throws Exception {
        if (instance == null) {
            instance = new AdbManager(context.getApplicationContext());
        }
        return instance;
    }

    private AdbManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        setHostAddress("127.0.0.1");

        SharedPreferences sp = context.getSharedPreferences("adb_identity", Context.MODE_PRIVATE);
        String savedKey = sp.getString("key", null);
        String savedCert = sp.getString("cert", null);

        if (savedKey != null && savedCert != null) {
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.decode(savedKey, Base64.NO_WRAP)));
            certificate = CertificateFactory.getInstance("X.509").generateCertificate(
                    new ByteArrayInputStream(Base64.decode(savedCert, Base64.NO_WRAP)));
        } else {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();

            X500Name name = new X500Name("CN=QuestPredTuner");
            long now = System.currentTimeMillis();
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    name,
                    BigInteger.valueOf(now),
                    new Date(now - 86_400_000L),
                    new Date(now + 3650L * 86_400_000L),
                    name,
                    kp.getPublic());
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());

            privateKey = kp.getPrivate();
            certificate = new JcaX509CertificateConverter().getCertificate(builder.build(signer));

            sp.edit()
                    .putString("key", Base64.encodeToString(privateKey.getEncoded(), Base64.NO_WRAP))
                    .putString("cert", Base64.encodeToString(certificate.getEncoded(), Base64.NO_WRAP))
                    .apply();
        }
    }

    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @Override
    protected String getDeviceName() {
        return "QuestPredTuner";
    }

    /** Runs a one-shot shell command on the headset and returns its output. */
    public String shell(String command) throws Exception {
        AdbStream stream = openStream("shell:" + command);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            InputStream in = stream.openInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } catch (IOException endOfStream) {
            // libadb reports the remote side closing the stream this way on some versions
        } finally {
            try {
                stream.close();
            } catch (Exception ignored) {
            }
        }
        return out.toString("UTF-8").trim();
    }
}
