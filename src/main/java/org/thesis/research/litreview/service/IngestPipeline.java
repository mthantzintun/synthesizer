package org.thesis.research.litreview.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.thesis.research.litreview.config.synthesis.EmbeddingConfig.EmbeddingBatcher;
import org.thesis.research.litreview.entity.ChunkJdbcRepository;
import org.thesis.research.litreview.entity.Paper;
import org.thesis.research.litreview.entity.PaperRepository;
import org.thesis.research.litreview.ingest.bibtex.LibraryLoader;
import org.thesis.research.litreview.ingest.bibtex.LibraryReport;
import org.thesis.research.litreview.ingest.bibtex.PaperMeta;
import org.thesis.research.litreview.util.Chunk;
import org.thesis.research.litreview.venue.grobid.GrobidClient;
import org.thesis.research.litreview.venue.grobid.TeiDocument;
import org.thesis.research.litreview.venue.grobid.TeiParser;

/**
 * Phase 2-3 orchestration: BibTeX + PDF in, embedded chunks out.
 *
 * <p>Runs the whole corpus in one pass and never lets one bad paper stop the
 * batch. Every failure is attributed to a single citekey, written to the paper
 * row, and skipped - a 30-page PDF that GROBID cannot read is a normal event in
 * a real library, not an exception that should abort 26 good papers.
 *
 * <p>Status advances one step at a time ({@code METADATA_OK} &rarr;
 * {@code GROBID_OK} &rarr; {@code CHUNKED} &rarr; {@code EMBEDDED}) so that a
 * crash mid-run leaves every paper at a truthful state, and a re-run resumes
 * from whatever step it actually reached instead of redoing everything.
 */
@Service
public class IngestPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestPipeline.class);

    /** Statuses at or beyond which a paper is fully ingested. */
    private static final List<Paper.IngestStatus> COMPLETE =
            List.of(Paper.IngestStatus.EMBEDDED, Paper.IngestStatus.EXTRACTED);

    private final LibraryLoader libraryLoader;
    private final PaperRepository papers;
    private final GrobidClient grobid;
    private final TeiParser teiParser;
    private final ChunkingService chunkingService;
    private final EmbeddingBatcher embeddings;
    private final ChunkJdbcRepository chunkJdbc;

    public IngestPipeline(LibraryLoader libraryLoader, PaperRepository papers, GrobidClient grobid,
            TeiParser teiParser, ChunkingService chunkingService, EmbeddingBatcher embeddings,
            ChunkJdbcRepository chunkJdbc) {
        this.libraryLoader = libraryLoader;
        this.papers = papers;
        this.grobid = grobid;
        this.teiParser = teiParser;
        this.chunkingService = chunkingService;
        this.embeddings = embeddings;
        this.chunkJdbc = chunkJdbc;
    }

    // ------------------------------------------------------------------ batch

    /**
     * Ingests the whole configured library.
     *
     * @param force re-ingest papers that are already complete, replacing their
     *              chunks; without it those papers are skipped and the run is
     *              cheap to repeat
     */
    public IngestSummary runAll(boolean force) {
        LibraryReport report = libraryLoader.load();
        log.info("Ingest: {} ingestable entries", report.valid().size());

        int ingested = 0;
        int skipped = 0;
        List<String> failed = new ArrayList<>();

        for (PaperMeta meta : report.valid()) {
            try {
                if (ingestOne(meta, force)) {
                    ingested++;
                }
                else {
                    skipped++;
                }
            }
            catch (RuntimeException e) {
                log.warn("Ingest failed for {}: {}", meta.citekey(), e.getMessage());
                failed.add(meta.citekey());
                markFailed(meta, e);
            }
        }

        IngestSummary summary = new IngestSummary(ingested, skipped, failed,
                Map.copyOf(statusCounts()));
        log.info(summary.describe());
        return summary;
    }

    /** Ingests one entry by citekey, for a targeted retry after a failure. */
    public IngestSummary runOne(String citekey, boolean force) {
        LibraryReport report = libraryLoader.load();
        PaperMeta meta = report.valid().stream()
                .filter(p -> citekey.equals(p.citekey()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No ingestable library entry with citekey " + citekey));

        try {
            boolean ingested = ingestOne(meta, force);
            return new IngestSummary(ingested ? 1 : 0, ingested ? 0 : 1, List.of(), Map.of());
        }
        catch (RuntimeException e) {
            markFailed(meta, e);
            return new IngestSummary(0, 0, List.of(citekey), Map.of());
        }
    }

    /**
     * @return true when the paper was ingested, false when it was skipped as
     *         already complete
     */
    public boolean ingestOne(PaperMeta meta, boolean force) {
        Paper paper = upsertMetadata(meta);

        if (!force && COMPLETE.contains(paper.getStatus())) {
            log.debug("{}: already {} - skipping", meta.citekey(), paper.getStatus());
            return false;
        }

        try {
            TeiDocument doc = parse(paper, meta);
            List<Chunk> paperChunks = chunk(paper, doc);
            embed(paper, paperChunks);
        }
        catch (RuntimeException e) {
            markFailed(meta, e);
            throw e;
        }
        return true;
    }

    // ------------------------------------------------------------------ steps

    /**
     * Creates or refreshes the {@code paper} row from BibTeX metadata.
     *
     * <p>Metadata is rewritten on every run, including for complete papers:
     * correcting a year or DOI in library.bib should be enough to fix it here,
     * without a forced re-ingest of the PDF.
     */
    private Paper upsertMetadata(PaperMeta meta) {
        Paper paper = papers.findByCitekey(meta.citekey())
                .orElseGet(() -> Paper.of(meta.citekey(), meta.doi(), meta.title(),
                        meta.authors() == null ? "" : meta.authors(), meta.year(),
                        meta.venue(), null));

        paper.setDoi(meta.doi());
        paper.setTitle(meta.title());
        paper.setAuthors(meta.authors() == null ? "" : meta.authors());
        paper.setYear(meta.year());
        paper.setVenue(meta.venue());
        paper.setPdfPath(meta.pdfPath() == null ? null : meta.pdfPath().toString());

        if (paper.getStatus() == Paper.IngestStatus.PENDING
                || paper.getStatus() == Paper.IngestStatus.FAILED) {
            paper.markStatus(Paper.IngestStatus.METADATA_OK);
        }
        return papers.save(paper);
    }

    private TeiDocument parse(Paper paper, PaperMeta meta) {
        Path pdf = meta.pdfPath();
        String xml = grobid.extractTeiXml(pdf);
        TeiDocument doc = teiParser.parse(xml);

        if (doc.looksEmpty()) {
            // Almost always a scanned PDF with no text layer. Failing loudly
            // beats ingesting a handful of garbled lines into the corpus.
            throw new IngestException("GROBID produced no usable body text ("
                    + doc.charCount() + " chars) - likely a scanned PDF");
        }

        paper.markStatus(Paper.IngestStatus.GROBID_OK);
        papers.save(paper);
        return doc;
    }

    private List<Chunk> chunk(Paper paper, TeiDocument doc) {
        List<Chunk> paperChunks = chunkingService.chunk(paper.getCitekey(), doc);
        if (paperChunks.isEmpty()) {
            throw new IngestException("Chunking produced no chunks");
        }
        paper.markStatus(Paper.IngestStatus.CHUNKED);
        papers.save(paper);
        return paperChunks;
    }

    /**
     * Embeds and persists the chunks, replacing any previous version.
     *
     * <p>The replace is delegated whole to {@link ChunkJdbcRepository} rather
     * than split between JPA and JDBC: one writer for one row. Deleting first
     * also means a re-chunk cannot leave a paper with two generations of
     * chunks, which would double every count downstream and make retrieval
     * return near-duplicate evidence.
     */
    private void embed(Paper paper, List<Chunk> paperChunks) {
        List<String> texts = paperChunks.stream().map(Chunk::embeddingText).toList();
        List<float[]> vectors = embeddings.embedAll(texts);

        // Embedding happens outside the write so the (slow, CPU-bound) ONNX
        // batch does not hold a database transaction open.
        chunkJdbc.replaceBatch(paper.getId(), paperChunks, vectors);

        paper.markStatus(Paper.IngestStatus.EMBEDDED);
        papers.save(paper);

        log.info("{}: {} chunks embedded", paper.getCitekey(), paperChunks.size());
    }

    // ---------------------------------------------------------------- helpers

    private void markFailed(PaperMeta meta, RuntimeException e) {
        try {
            papers.findByCitekey(meta.citekey()).ifPresent(paper -> {
                paper.markFailed(e.getMessage());
                papers.save(paper);
            });
        }
        catch (RuntimeException nested) {
            // If even recording the failure fails, the log line is the last
            // resort - swallowing here keeps the batch moving.
            log.error("Could not record failure for {}: {}", meta.citekey(), nested.getMessage());
        }
    }

    private Map<Paper.IngestStatus, Integer> statusCounts() {
        Map<Paper.IngestStatus, Integer> counts = new EnumMap<>(Paper.IngestStatus.class);
        for (Paper.IngestStatus status : Paper.IngestStatus.values()) {
            counts.put(status, papers.findByStatus(status).size());
        }
        return counts;
    }

    /** Per-status paper counts, for the status endpoint. */
    public Map<Paper.IngestStatus, Integer> statusReport() {
        return statusCounts();
    }

    // ------------------------------------------------------------------ types

    /**
     * Outcome of one batch run.
     *
     * @param ingested  papers that were read, chunked and embedded
     * @param skipped   papers already complete
     * @param failed    citekeys that failed, in encounter order
     * @param byStatus  paper counts per status after the run
     */
    public record IngestSummary(int ingested, int skipped, List<String> failed,
            Map<Paper.IngestStatus, Integer> byStatus) {

        public String describe() {
            StringBuilder sb = new StringBuilder("Ingest: %d ingested, %d skipped, %d failed"
                    .formatted(ingested, skipped, failed.size()));
            if (!failed.isEmpty()) {
                sb.append(" (").append(String.join(", ", failed)).append(')');
            }
            if (!byStatus.isEmpty()) {
                sb.append("\n  status: ").append(byStatus);
            }
            return sb.toString();
        }
    }

    /** A paper-level ingest failure: bad parse, empty text, no chunks. */
    public static class IngestException extends RuntimeException {
        public IngestException(String message) {
            super(message);
        }

        public IngestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
