package org.thesis.research.litreview.service.extraction;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thesis.research.litreview.entity.ApiCallLogEntity;
import org.thesis.research.litreview.entity.ApiCallLogRepository;
import org.thesis.research.litreview.entity.ExtractionEntity;
import org.thesis.research.litreview.entity.Paper;
import org.thesis.research.litreview.entity.PaperRepository;
import org.thesis.research.litreview.venue.grobid.GrobidClient;
import org.thesis.research.litreview.venue.grobid.TeiDocument;
import org.thesis.research.litreview.venue.grobid.TeiParser;

/**
 * Phase 4: turns one paper's full text into a concept-matrix row.
 *
 * <p>Re-reads the TEI from GROBID's disk cache rather than from the database.
 * The cache is keyed by the PDF's SHA-256 and is already warm after ingest, so
 * this is a local file read - and it keeps the chunk table out of the
 * extraction path entirely, which matters because extraction wants whole
 * sections while chunks are deliberately cut to a retrieval-friendly size.
 */
@Service
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    /**
     * Rough character budget for the evidence block (~15k tokens).
     *
     * <p>Sections are added in priority order until this is reached. A paper
     * longer than this loses its least relevant sections, never a truncated
     * middle - half a Methods section would invite the model to fill the gap.
     */
    private static final int EVIDENCE_BUDGET_CHARS = 60_000;

    /**
     * Sections in descending order of value to a concept matrix. Method and
     * results outrank the introduction because research question, constructs
     * and finding are almost always restated there with more precision.
     */
    private static final List<TeiDocument.CanonicalSection> SECTION_PRIORITY = List.of(
            TeiDocument.CanonicalSection.ABSTRACT,
            TeiDocument.CanonicalSection.METHOD,
            TeiDocument.CanonicalSection.RESULTS,
            TeiDocument.CanonicalSection.DISCUSSION,
            TeiDocument.CanonicalSection.LIMITATIONS,
            TeiDocument.CanonicalSection.FUTURE_WORK,
            TeiDocument.CanonicalSection.CONCLUSION,
            TeiDocument.CanonicalSection.INTRODUCTION,
            TeiDocument.CanonicalSection.THEORY,
            TeiDocument.CanonicalSection.RELATED_WORK,
            TeiDocument.CanonicalSection.OTHER);

    private final ChatClient extractionClient;
    private final ExtractionRepository extractions;
    private final PaperRepository papers;
    private final ApiCallLogRepository apiLog;
    private final GrobidClient grobid;
    private final TeiParser teiParser;
    private final String modelName;

    public ExtractionService(@Qualifier("extractionClient") ChatClient extractionClient,
            ExtractionRepository extractions, PaperRepository papers, ApiCallLogRepository apiLog,
            GrobidClient grobid, TeiParser teiParser,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String modelName) {
        this.extractionClient = extractionClient;
        this.extractions = extractions;
        this.papers = papers;
        this.apiLog = apiLog;
        this.grobid = grobid;
        this.teiParser = teiParser;
        this.modelName = modelName;
    }

    // ------------------------------------------------------------ single paper

    /**
     * Extracts one paper, upserting its {@code extraction} row.
     *
     * <p>Deliberately <em>not</em> {@code @Transactional}: the body makes a
     * network call that can take a minute, and holding a pooled connection and
     * an open transaction across it would starve the pool during a corpus run.
     * The write is a single repository call at the end, which is its own
     * transaction.
     *
     * @throws ExtractionException when the paper cannot be read or the model
     *         returns something unusable; the caller decides whether to mark
     *         the paper failed
     */
    public ExtractionEntity extractFor(Paper paper) {
        TeiDocument doc = loadTei(paper);
        String evidence = buildEvidence(doc);

        if (evidence.isBlank()) {
            throw new ExtractionException("No usable text for " + paper.getCitekey());
        }

        log.info("Extracting {} ({} chars of evidence)", paper.getCitekey(), evidence.length());

        ResponseEntity<ChatResponse, Extraction> response;
        try {
            response = extractionClient.prompt()
                    .user(userPrompt(paper, evidence))
                    .call()
                    .responseEntity(Extraction.class);
        }
        catch (RuntimeException e) {
            recordFailure(paper, e.getMessage());
            throw new ExtractionException("LLM call failed for " + paper.getCitekey(), e);
        }

        Extraction extraction = response.entity();
        if (extraction == null) {
            recordFailure(paper, "model returned no parseable entity");
            throw new ExtractionException("Model returned nothing parseable for " + paper.getCitekey());
        }

        recordSuccess(paper, response.response());

        ExtractionEntity entity = save(paper, extraction.withModelUsed(modelName));
        paper.markStatus(Paper.IngestStatus.EXTRACTED);
        papers.save(paper);

        log.info("Extracted {}: {} construct(s), finding={}",
                paper.getCitekey(), entity.getConstructs() == null ? 0 : entity.getConstructs().size(),
                entity.getKeyFinding() == null ? "none" : "yes");
        return entity;
    }

    /**
     * Extracts every paper that is embedded but not yet extracted.
     *
     * <p>Failures are recorded and skipped, not propagated: a single
     * uncooperative paper must not stop a 27-paper run, and the
     * {@code api_call_log} plus the paper's own {@code FAILED} status already
     * record what went wrong for a later retry.
     *
     * @return the rows that were successfully written
     */
    public List<ExtractionEntity> extractAllPending() {
        List<Paper> pending = papers.findByStatus(Paper.IngestStatus.EMBEDDED);
        log.info("Extraction: {} paper(s) pending", pending.size());

        List<ExtractionEntity> done = new ArrayList<>();
        for (Paper paper : pending) {
            try {
                done.add(extractFor(paper));
            }
            catch (RuntimeException e) {
                log.warn("Extraction failed for {}: {}", paper.getCitekey(), e.getMessage());
                paper.markFailed("extraction: " + e.getMessage());
                papers.save(paper);
            }
        }
        log.info("Extraction: {}/{} succeeded", done.size(), pending.size());
        return done;
    }

    /** Extracts one paper by citekey, for a targeted re-run. */
    public ExtractionEntity extractByCitekey(String citekey) {
        Paper paper = papers.findByCitekey(citekey)
                .orElseThrow(() -> new ExtractionException("No paper with citekey " + citekey));
        return extractFor(paper);
    }

    // -------------------------------------------------------------- reads

    public Optional<ExtractionEntity> find(String citekey) {
        return extractions.findByPaperCitekey(citekey);
    }

    public List<ExtractionEntity> findAll() {
        return extractions.findAll();
    }

    /**
     * Papers whose extraction names the given construct.
     *
     * <p>Exact-match array overlap rather than a fuzzy text search: construct
     * labels come from the extraction step's own vocabulary, so a caller
     * filtering on one is asking about that exact label.
     */
    public List<ExtractionEntity> findByConstruct(String construct) {
        return extractions.findByAnyConstruct(new String[] { construct });
    }

    // ------------------------------------------------------------ internals

    private TeiDocument loadTei(Paper paper) {
        String pdfPath = paper.getPdfPath();
        if (pdfPath == null || pdfPath.isBlank()) {
            throw new ExtractionException("Paper " + paper.getCitekey() + " has no PDF path");
        }
        Path pdf = Path.of(pdfPath);
        // Served from the SHA-256-keyed cache written during ingest.
        String xml = grobid.extractTeiXml(pdf);
        return teiParser.parse(xml);
    }

    /**
     * Assembles the evidence block, best sections first.
     *
     * <p>Each section is labelled with its canonical slot so the model can tell
     * a stated limitation from a passing remark - the same sentence means
     * different things under "Limitations" than under "Introduction".
     */
    private String buildEvidence(TeiDocument doc) {
        StringBuilder sb = new StringBuilder();
        int used = 0;

        for (TeiDocument.CanonicalSection slot : SECTION_PRIORITY) {
            if (used >= EVIDENCE_BUDGET_CHARS) {
                break;
            }
            Optional<String> text = doc.sectionText(slot);
            if (text.isEmpty()) {
                continue;
            }
            String body = text.get();
            int remaining = EVIDENCE_BUDGET_CHARS - used;
            if (body.length() > remaining) {
                body = body.substring(0, remaining);
            }
            sb.append("### ").append(slot.name()).append('\n').append(body).append("\n\n");
            used += body.length();
        }
        return sb.toString().trim();
    }

    private String userPrompt(Paper paper, String evidence) {
        return """
                Paper: %s
                Title: %s
                Authors: %s
                Year: %s
                Venue: %s

                Extract the concept-matrix row for this paper from the text below.
                Follow the schema exactly. Return null for any field the paper does not
                explicitly state - a null is a correct answer, an invented value is not.

                --- BEGIN PAPER TEXT ---
                %s
                --- END PAPER TEXT ---
                """.formatted(
                paper.getCitekey(),
                paper.getTitle(),
                paper.getAuthors(),
                paper.getYear() == null ? "unknown" : paper.getYear(),
                paper.getVenue() == null ? "unknown" : paper.getVenue(),
                evidence);
    }

    /** Upserts the row - one extraction per paper, re-running replaces it. */
    private ExtractionEntity save(Paper paper, Extraction extraction) {
        ExtractionEntity entity = extractions.findByPaperId(paper.getId())
                .orElseGet(() -> new ExtractionEntity(paper));

        entity.setResearchQuestion(extraction.researchQuestion());
        entity.setTheoreticalLens(extraction.theoreticalLens());
        entity.setMethod(extraction.method());
        // An empty array and a null array behave differently under `&&`, so
        // normalise to an empty list and let the overlap query be total.
        entity.setConstructs(extraction.constructs() == null ? List.of() : extraction.constructs());
        entity.setKeyFinding(extraction.keyFinding());
        entity.setStatedLimitation(extraction.statedLimitation());
        entity.setFutureWorkSuggested(extraction.futureWorkSuggested());
        entity.setContextSector(extraction.contextSector());
        entity.setSupportingQuote(extraction.supportingQuote());
        entity.setQuotePage(extraction.quotePage());
        entity.setModelUsed(extraction.modelUsed());
        entity.setExtractedAt(Instant.now());
        return extractions.save(entity);
    }

    private void recordSuccess(Paper paper, ChatResponse response) {
        Integer in = null;
        Integer out = null;
        if (response != null && response.getMetadata() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                in = usage.getPromptTokens();
                out = usage.getCompletionTokens();
            }
        }
        logBestEffort(ApiCallLogEntity.success(paper, "extraction", modelName, in, out));
    }

    private void recordFailure(Paper paper, String message) {
        logBestEffort(ApiCallLogEntity.failure(paper, "extraction", modelName, message));
    }

    /**
     * Writes a cost/audit row, swallowing any error.
     *
     * <p>Accounting must never be able to fail the work it accounts for - a
     * full disk or a dropped connection here would otherwise turn a successful
     * extraction into a failed one.
     */
    private void logBestEffort(ApiCallLogEntity entry) {
        try {
            apiLog.save(entry);
        }
        catch (RuntimeException e) {
            log.warn("Could not write api_call_log row: {}", e.getMessage());
        }
    }

    /** The paper could not be read, or the model produced nothing usable. */
    public static class ExtractionException extends RuntimeException {
        public ExtractionException(String message) {
            super(message);
        }

        public ExtractionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
