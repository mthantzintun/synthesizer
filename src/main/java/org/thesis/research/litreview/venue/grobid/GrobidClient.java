package org.thesis.research.litreview.venue.grobid;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.thesis.research.litreview.config.LitreviewProperties;

/**
 * Thin wrapper over the GROBID REST API.
 *
 * <p>Two things beyond a plain POST matter here:
 * <ul>
 *   <li><b>Caching.</b> Full-text extraction of a 30-page PDF takes 10-60s.
 *       Responses are cached on disk keyed by the SHA-256 of the PDF bytes, so
 *       re-running the pipeline to re-tune chunking never re-hits GROBID.</li>
 *   <li><b>Retries.</b> GROBID returns 503 while its models are still loading
 *       and can time out on pathological PDFs; both are worth one retry.</li>
 * </ul>
 */
@Service
public class GrobidClient {

    private static final Logger log = LoggerFactory.getLogger(GrobidClient.class);

    private final RestClient restClient;
    private final LitreviewProperties.Grobid config;
    private final Path cacheDir;

    public GrobidClient(LitreviewProperties props) {
        this.config = props.grobid();
        this.cacheDir = Path.of(props.teiCacheDir()).toAbsolutePath().normalize();

        // GROBID full-text parsing is slow by nature, so the read timeout has to
        // be generous - the default 30s would abort on any long paper.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(config.timeout());

        this.restClient = RestClient.builder()
                .baseUrl(config.url())
                .requestFactory(factory)
                .build();
    }

    /** @return true when GROBID is up and has finished loading its models. */
    public boolean isAlive() {
        try {
            String body = restClient.get().uri("/api/isalive").retrieve().body(String.class);
            return body != null && body.toLowerCase().contains("true");
        }
        catch (RestClientException e) {
            log.warn("GROBID is not reachable at {}: {}", config.url(), e.getMessage());
            return false;
        }
    }

    /**
     * Runs {@code processFulltextDocument} on a PDF, returning TEI XML.
     * Served from the disk cache when the same bytes were processed before.
     */
    public String extractTeiXml(Path pdf) {
        byte[] bytes = read(pdf);
        String hash = sha256(bytes);
        Path cached = cacheDir.resolve(hash + ".tei.xml");

        if (Files.isReadable(cached)) {
            log.debug("TEI cache hit for {} ({})", pdf.getFileName(), hash.substring(0, 8));
            try {
                return Files.readString(cached, StandardCharsets.UTF_8);
            }
            catch (IOException e) {
                log.warn("Could not read cached TEI {}, re-fetching: {}", cached, e.getMessage());
            }
        }

        String tei = post(bytes, pdf.getFileName().toString());
        writeCache(cached, tei);
        return tei;
    }

    public String extractTeiXml(byte[] pdfBytes, String filename) {
        String hash = sha256(pdfBytes);
        Path cached = cacheDir.resolve(hash + ".tei.xml");
        if (Files.isReadable(cached)) {
            try {
                return Files.readString(cached, StandardCharsets.UTF_8);
            }
            catch (IOException ignored) {
                // fall through to a fresh fetch
            }
        }
        String tei = post(pdfBytes, filename);
        writeCache(cached, tei);
        return tei;
    }

    private String post(byte[] pdfBytes, String filename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("input", new ByteArrayResource(pdfBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        body.add("consolidateHeader", config.consolidateHeader() ? "1" : "0");
        // citation consolidation hits external services per reference; it roughly
        // triples runtime and we never use the enriched bibliography
        body.add("consolidateCitations", config.consolidateCitations() ? "1" : "0");
        if (config.segmentSentences()) {
            body.add("segmentSentences", "1");
        }
        // page coordinates are what let us attach a page number to every quote
        body.add("teiCoordinates", "p");
        body.add("includeRawAffiliations", "0");

        RestClientException last = null;
        for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
            try {
                long started = System.currentTimeMillis();
                String tei = restClient.post()
                        .uri(URI.create(config.url() + "/api/processFulltextDocument"))
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(body)
                        .retrieve()
                        .body(String.class);

                if (tei == null || tei.isBlank()) {
                    throw new GrobidException("GROBID returned an empty body for " + filename);
                }
                log.info("GROBID parsed {} in {} ms ({} KB TEI)", filename,
                        System.currentTimeMillis() - started, tei.length() / 1024);
                return tei;
            }
            catch (RestClientException e) {
                last = e;
                if (attempt < config.maxRetries()) {
                    long backoff = 2000L * (attempt + 1);
                    log.warn("GROBID call for {} failed (attempt {}/{}), retrying in {} ms: {}",
                            filename, attempt + 1, config.maxRetries() + 1, backoff, e.getMessage());
                    sleep(backoff);
                }
            }
        }
        throw new GrobidException("GROBID failed for " + filename + " after "
                + (config.maxRetries() + 1) + " attempts", last);
    }

    private void writeCache(Path target, String tei) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, tei, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            // a cache write failure must never fail the ingest
            log.warn("Could not cache TEI to {}: {}", target, e.getMessage());
        }
    }

    private static byte[] read(Path pdf) {
        try {
            return Files.readAllBytes(pdf);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Could not read PDF " + pdf, e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new GrobidException("Interrupted while waiting to retry GROBID", ie);
        }
    }

    /** Unrecoverable GROBID failure for a single document. */
    public static class GrobidException extends RuntimeException {
        public GrobidException(String message) {
            super(message);
        }

        public GrobidException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
