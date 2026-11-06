package com.veneer.core;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * HTTPS without a domain: a certificate this server made for itself, kept in a file so its fingerprint stays the same.
 * The JDK has no public API to create a certificate, so the few fields one needs are written out here as DER.
 */
public final class SelfSignedTls {

    private static final char[] PASSWORD = "veneer".toCharArray(); // Keystores need one; the file's permissions protect it
    private static final String ALIAS = "panel";
    private static final long VALID_MS = 3650L * 86_400_000L;
    private static final byte[] ECDSA_SHA256 = {0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 4, 3, 2};
    private static final byte[] COMMON_NAME = {0x55, 4, 3};
    private static final byte[] SUBJECT_ALT_NAME = {0x55, 0x1D, 0x11};

    private final SSLContext context;
    private final String fingerprint;

    private SelfSignedTls(SSLContext context, String fingerprint) {
        this.context = context;
        this.fingerprint = fingerprint;
    }

    public SSLContext context() { return context; }

    /** SHA-256 of the certificate as browsers show it: AB:CD:... */
    public String fingerprint() { return fingerprint; }

    /** The certificate in {@code file} when it is still valid and covers {@code names}, otherwise a new one saved there. */
    public static SelfSignedTls load(Path file, Collection<String> names) throws GeneralSecurityException, IOException {
        KeyStore store = read(file, names);
        if (store == null) store = create(file, names);
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(store.getCertificate(ALIAS).getEncoded());
        return new SelfSignedTls(context, HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest));
    }

    private static KeyStore read(Path file, Collection<String> names) {
        if (!Files.isRegularFile(file)) return null;
        try (InputStream in = Files.newInputStream(file)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, PASSWORD);
            X509Certificate cert = (X509Certificate) store.getCertificate(ALIAS);
            cert.checkValidity();
            Set<String> covered = new HashSet<>();
            for (List<?> san : cert.getSubjectAlternativeNames()) covered.add(String.valueOf(san.get(1)).toLowerCase(Locale.ROOT));
            for (String name : names) if (!covered.contains(name.toLowerCase(Locale.ROOT))) return null;
            return store;
        } catch (Exception e) {
            return null; // Damaged, expired or for another address: replaced by a new one
        }
    }

    private static KeyStore create(Path file, Collection<String> names) throws GeneralSecurityException, IOException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        Certificate cert = certificate(pair, names);
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry(ALIAS, pair.getPrivate(), PASSWORD, new Certificate[]{cert});
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, PASSWORD);
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Not a POSIX file system
        }
        return store;
    }

    private static Certificate certificate(KeyPair pair, Collection<String> names) throws GeneralSecurityException, IOException {
        byte[] algorithm = der(0x30, der(0x06, ECDSA_SHA256));
        byte[] name = der(0x30, der(0x31, der(0x30, der(0x06, COMMON_NAME), der(0x0C, "Veneer panel".getBytes(StandardCharsets.UTF_8)))));
        long now = System.currentTimeMillis();
        byte[] validity = der(0x30, time(now - 86_400_000L), time(now + VALID_MS));
        byte[] serial = der(0x02, new BigInteger(64, new SecureRandom()).setBit(63).toByteArray());
        byte[] extensions = der(0xA3, der(0x30, der(0x30, der(0x06, SUBJECT_ALT_NAME), der(0x04, altNames(names)))));
        byte[] tbs = der(0x30, der(0xA0, der(0x02, new byte[]{2})), serial, algorithm, name, validity, name,
                pair.getPublic().getEncoded(), extensions);
        byte[] signature = sign(pair.getPrivate(), tbs);
        byte[] bits = new byte[signature.length + 1]; // The first byte counts unused bits: none
        System.arraycopy(signature, 0, bits, 1, signature.length);
        return CertificateFactory.getInstance("X.509").generateCertificate(
                new ByteArrayInputStream(der(0x30, tbs, algorithm, der(0x03, bits))));
    }

    private static byte[] sign(PrivateKey key, byte[] data) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(data);
        return signer.sign();
    }

    private static byte[] altNames(Collection<String> names) throws IOException {
        List<byte[]> parts = new ArrayList<>();
        for (String name : new HashSet<>(names)) {
            boolean ip = name.contains(":") || name.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
            // A literal address is parsed without a lookup
            parts.add(ip ? der(0x87, InetAddress.getByName(name).getAddress()) : der(0x82, name.getBytes(StandardCharsets.US_ASCII)));
        }
        return der(0x30, parts.toArray(new byte[0][]));
    }

    private static byte[] time(long millis) throws IOException {
        SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return der(0x17, format.format(new Date(millis)).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] der(int tag, byte[]... content) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] part : content) body.write(part);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = body.size();
        if (length < 0x80) {
            out.write(length);
        } else {
            byte[] size = BigInteger.valueOf(length).toByteArray();
            int skip = size[0] == 0 ? 1 : 0;
            out.write(0x80 | (size.length - skip));
            out.write(size, skip, size.length - skip);
        }
        body.writeTo(out);
        return out.toByteArray();
    }
}
