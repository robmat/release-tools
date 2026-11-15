package com.batodev.releasetools

import static org.junit.Assert.assertArrayEquals
import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertTrue

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import org.gradle.api.logging.Logging
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator

/**
 * PlayApi's resumable upload against a stand-in for Google's server that breaks the connection in the middle of one chunk
 * (keeping part of it, as a real reset can) and answers another with a 503: the bundle must still arrive whole, each chunk
 * resent from where the server says it has the upload.
 */
class PlayApiTest {

    private static final int CHUNK = 256 * 1024
    private static final int KEPT_OF_BROKEN_CHUNK = 100_000

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder()

    private HttpServer server
    private String host
    private final ByteArrayOutputStream received = new ByteArrayOutputStream()
    private long total
    private int chunks
    private int breaks
    private boolean signedIn
    // Asserted by the test, not in the server's thread, where a failure would only break the connection.
    private int faults

    @Before
    void start() {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/", { HttpExchange exchange -> handle(exchange) } as HttpHandler)
        server.start()
        host = "http://127.0.0.1:${server.address.port}"
    }

    @After
    void stop() {
        server.stop(0)
    }

    @Test
    void uploadSurvivesABrokenChunkAndAServerError() {
        byte[] bundle = new byte[4 * CHUNK + 1000]
        new Random(1).nextBytes(bundle)
        File file = folder.newFile("app.aab")
        file.bytes = bundle
        total = bundle.length

        long versionCode = new PlayApi(credentials(), Logging.getLogger(PlayApiTest), host, CHUNK).uploadBundle("app", "e1", file)

        assertEquals(7L, versionCode)
        assertArrayEquals(bundle, received.toByteArray())
        assertEquals(2, breaks)
        assertTrue(signedIn)
        assertEquals(0, faults)
    }

    private void handle(HttpExchange exchange) {
        String path = exchange.requestURI.path
        byte[] body = exchange.requestBody.bytes
        if (path == "/token") {
            signedIn = new String(body, StandardCharsets.UTF_8).contains("assertion=")
            reply(exchange, 200, [access_token: "token"])
        } else if (path.endsWith("/bundles")) {
            exchange.responseHeaders.add("Location", "${host}/session")
            reply(exchange, 200, [:])
        } else {
            faults += exchange.requestHeaders.getFirst("Authorization") == "Bearer token" ? 0 : 1
            chunk(exchange, exchange.requestHeaders.getFirst("Content-Range"), body)
        }
    }

    /** A chunk, or with `bytes * /total` the question how far the upload is. */
    private void chunk(HttpExchange exchange, String range, byte[] body) {
        if (range.startsWith("bytes */")) {
            progress(exchange)
            return
        }
        // Each chunk from where the server has the upload.
        faults += (range.split(/[ -]/)[1] as long) == received.size() ? 0 : 1
        chunks++
        if (chunks == 2) {
            breaks++
            received.write(body, 0, KEPT_OF_BROKEN_CHUNK)
            exchange.close()
        } else if (chunks == 4) {
            breaks++
            reply(exchange, 503, [error: "unavailable"])
        } else {
            received.write(body)
            progress(exchange)
        }
    }

    private void progress(HttpExchange exchange) {
        if (received.size() == total) {
            reply(exchange, 200, [versionCode: 7, sha256: "abc"])
        } else {
            exchange.responseHeaders.add("Range", "bytes=0-${received.size() - 1}")
            reply(exchange, 308, null)
        }
    }

    private static void reply(HttpExchange exchange, int status, Map json) {
        byte[] bytes = json == null ? new byte[0] : JsonOutput.toJson(json).getBytes(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length)
        exchange.responseBody.withStream { it.write(bytes) }
    }

    private File credentials() {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        String key = Base64.mimeEncoder.encodeToString(generator.generateKeyPair().private.encoded)
        File file = folder.newFile("key.json")
        file.text = JsonOutput.toJson([
                client_email: "test@example.com",
                token_uri   : "${host}/token",
                private_key : "-----BEGIN PRIVATE KEY-----\n${key}\n-----END PRIVATE KEY-----\n",
        ])
        file
    }
}
