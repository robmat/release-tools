package com.batodev.releasetools

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration

/**
 * The few Google Play Developer API calls a release needs, made directly - with a resumable bundle upload that survives a
 * broken connection, which Gradle Play Publisher's upload does not: on a big bundle (Starfold's is 1.3 GB) over a connection
 * that resets every few hundred megabytes, GPP failed every attempt ("Connection reset", "Error writing request body to
 * server") and started over from zero. Here each chunk that breaks is asked about (Google says how many bytes it has) and
 * sent again from there.
 *
 * Signs in as the service account itself (a JWT signed with its key, exchanged for an access token), so it needs nothing
 * beyond the JDK.
 */
class PlayApi {

    static final String HOST = "https://androidpublisher.googleapis.com"
    private static final String SCOPE = "https://www.googleapis.com/auth/androidpublisher"

    // A multiple of 256 KiB, as resumable uploads require; a broken chunk costs at most this much again.
    static final int CHUNK = 16 * 1024 * 1024
    private static final int TOKEN_LIFE_SECONDS = 3600
    // A token is renewed well before its hour is up, so a slow upload never sends a chunk with a dead one.
    private static final long TOKEN_RENEW_MILLIS = 45L * 60 * 1000
    // Breaks in a row, with no byte gained between them, before the upload gives up.
    private static final int MAX_FAILURES = 30
    private static final long MAX_WAIT_MILLIS = 60_000L
    private static final int PERCENT = 100
    private static final int SHOW_EVERY_PERCENT = 5

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMinutes(1)).build()
    private final Map credentials
    private final Logger logger
    private final String api
    private final String upload
    private final int chunkSize
    private String token
    private long tokenTime

    /** [host] and [chunkSize] are Google's and [CHUNK] but in tests, which talk to a stand-in server in smaller chunks. */
    PlayApi(File credentialsFile, Logger logger, String host = HOST, int chunkSize = CHUNK) {
        this.credentials = new JsonSlurper().parse(credentialsFile) as Map
        this.logger = logger
        this.api = "${host}/androidpublisher/v3/applications/"
        this.upload = "${host}/upload/androidpublisher/v3/applications/"
        this.chunkSize = chunkSize
    }

    String createEdit(String packageName) {
        call("POST", "${api}${packageName}/edits", [:]).id
    }

    /** Uploads [bundle] into [edit], resuming after every break; returns its versionCode. */
    long uploadBundle(String packageName, String edit, File bundle) {
        String session = startUpload("${upload}${packageName}/edits/${edit}/bundles?uploadType=resumable", bundle.length())
        Map uploaded = sendChunks(session, bundle)
        logger.lifecycle("Uploaded bundle: versionCode ${uploaded.versionCode}, sha256 ${uploaded.sha256}")
        uploaded.versionCode as long
    }

    void setTrack(String packageName, String edit, String track, Map release) {
        call("PUT", "${api}${packageName}/edits/${edit}/tracks/${track}", [track: track, releases: [release]])
    }

    void validate(String packageName, String edit) {
        call("POST", "${api}${packageName}/edits/${edit}:validate", null)
    }

    void commit(String packageName, String edit) {
        call("POST", "${api}${packageName}/edits/${edit}:commit", null)
    }

    void deleteEdit(String packageName, String edit) {
        send(request("${api}${packageName}/edits/${edit}").DELETE().build())
    }

    private String startUpload(String url, long size) {
        HttpResponse<String> response = send(request(url)
                .header("X-Upload-Content-Type", "application/octet-stream")
                .header("X-Upload-Content-Length", String.valueOf(size))
                .POST(HttpRequest.BodyPublishers.noBody()).build())
        checked(response)
        response.headers().firstValue("Location").orElseThrow { new GradleException("Play gave no upload session") }
    }

    private Map sendChunks(String session, File bundle) {
        long total = bundle.length()
        long sent = 0
        int failures = 0
        int shown = -1
        Map done = null
        while (done == null) {
            byte[] chunk = read(bundle, sent)
            HttpResponse<String> response = null
            try {
                response = send(request(session)
                        .header("Content-Range", "bytes ${sent}-${sent + chunk.length - 1}/${total}")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk)).build())
            } catch (IOException broken) {
                logger.lifecycle("Upload broke at ${sent.intdiv(1_000_000)} MB: ${broken.message}")
            }
            if (response == null || response.statusCode() >= 500) {
                failures = waitAfter(failures + 1)
                response = status(session, total)
            }
            if (response.statusCode() in [200, 201]) {
                done = json(response.body())
            } else if (response.statusCode() == 308) {
                long now = received(response)
                failures = now > sent ? 0 : failures
                sent = now
            } else {
                checked(response)
            }
            int percent = (sent * PERCENT / total) as int
            if (done == null && percent.intdiv(SHOW_EVERY_PERCENT) != shown.intdiv(SHOW_EVERY_PERCENT)) {
                shown = percent
                logger.lifecycle("Uploading bundle: ${percent}% (${sent.intdiv(1_000_000)} of ${total.intdiv(1_000_000)} MB)")
            }
        }
        done
    }

    /** How far Google has the upload, asked after a break: a 308 with its Range, or the finished upload's 200/201. */
    private HttpResponse<String> status(String session, long total) {
        int failures = 0
        HttpResponse<String> response = null
        while (response == null) {
            try {
                HttpResponse<String> asked = send(request(session).header("Content-Range", "bytes */${total}")
                        .PUT(HttpRequest.BodyPublishers.noBody()).build())
                response = asked.statusCode() >= 500 ? null : asked
            } catch (IOException broken) {
                logger.lifecycle("Asking the upload's status failed: ${broken.message}")
            }
            if (response == null) {
                failures = waitAfter(failures + 1)
            }
        }
        response
    }

    private int waitAfter(int failures) {
        if (failures > MAX_FAILURES) {
            throw new GradleException("The bundle's upload broke ${MAX_FAILURES} times in a row without getting further")
        }
        Thread.sleep(Math.min(MAX_WAIT_MILLIS, 2_000L * failures))
        failures
    }

    private static long received(HttpResponse<String> response) {
        response.headers().firstValue("Range").map { String range -> (range.split("-")[1] as long) + 1 }.orElse(0L)
    }

    private byte[] read(File file, long from) {
        RandomAccessFile input = new RandomAccessFile(file, "r")
        try {
            input.seek(from)
            byte[] chunk = new byte[(int) Math.min(chunkSize, file.length() - from)]
            input.readFully(chunk)
            chunk
        } finally {
            input.close()
        }
    }

    private Map call(String method, String url, Map body) {
        HttpRequest.BodyPublisher content = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body))
        HttpResponse<String> response = send(request(url).header("Content-Type", "application/json")
                .method(method, content).build())
        checked(response)
        response.body() ? json(response.body()) : [:]
    }

    private HttpRequest.Builder request(String url) {
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).header("Authorization", "Bearer ${accessToken()}")
    }

    private HttpResponse<String> send(HttpRequest request) {
        http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private static HttpResponse<String> checked(HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            throw new GradleException("Play's API answered ${response.statusCode()} to ${response.request().uri()}: ${response.body()}")
        }
        response
    }

    private static Map json(String text) {
        new JsonSlurper().parseText(text) as Map
    }

    private String accessToken() {
        if (token == null || System.currentTimeMillis() - tokenTime > TOKEN_RENEW_MILLIS) {
            tokenTime = System.currentTimeMillis()
            token = signIn()
        }
        token
    }

    private String signIn() {
        long now = tokenTime.intdiv(1000)
        String header = base64(JsonOutput.toJson([alg: "RS256", typ: "JWT"]).getBytes(StandardCharsets.UTF_8))
        String claims = base64(JsonOutput.toJson([
                iss  : credentials.client_email,
                scope: SCOPE,
                aud  : credentials.token_uri,
                iat  : now,
                exp  : now + TOKEN_LIFE_SECONDS,
        ]).getBytes(StandardCharsets.UTF_8))
        Signature signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey(credentials.private_key as String))
        signer.update("${header}.${claims}".getBytes(StandardCharsets.UTF_8))
        String jwt = "${header}.${claims}.${base64(signer.sign())}"
        String form = "grant_type=${URLEncoder.encode('urn:ietf:params:oauth:grant-type:jwt-bearer', 'UTF-8')}&assertion=${jwt}"
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(credentials.token_uri as String))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build())
        checked(response)
        json(response.body()).access_token
    }

    private static PrivateKey privateKey(String pem) {
        String body = pem.replaceAll(/-----[A-Z ]+-----/, "").replaceAll(/\s/, "")
        KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.decoder.decode(body)))
    }

    private static String base64(byte[] bytes) {
        Base64.urlEncoder.withoutPadding().encodeToString(bytes)
    }
}
