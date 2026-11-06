package com.veneer.core;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SelfSignedTlsTest {

    @TempDir Path dir;

    @Test
    void keepsItsCertificateUntilTheAddressChanges() throws Exception {
        Path file = dir.resolve("tls.p12");
        String first = SelfSignedTls.load(file, List.of("localhost", "127.0.0.1")).fingerprint();
        assertEquals(first, SelfSignedTls.load(file, List.of("localhost")).fingerprint());
        assertNotEquals(first, SelfSignedTls.load(file, List.of("play.example.com")).fingerprint());
    }

    @Test
    void aClientTrustingTheCertificateConnectsAndOthersAreRefused() throws Exception {
        Path file = dir.resolve("tls.p12");
        SelfSignedTls tls = SelfSignedTls.load(file, List.of("localhost", "127.0.0.1"));
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls.context()));
        server.createContext("/", ex -> { ex.sendResponseHeaders(204, -1); ex.close(); });
        server.start();
        try {
            URI uri = URI.create("https://localhost:" + server.getAddress().getPort() + "/");
            KeyStore stored = KeyStore.getInstance(file.toFile(), "veneer".toCharArray());
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(stored);
            SSLContext client = SSLContext.getInstance("TLS");
            client.init(null, trust.getTrustManagers(), null);
            HttpRequest request = HttpRequest.newBuilder(uri).build();
            assertEquals(204, HttpClient.newBuilder().sslContext(client).build()
                    .send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertThrows(SSLHandshakeException.class, () -> HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()));
        } finally {
            server.stop(0);
        }
    }
}
