package org.thesis.research.litreview.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.thesis.research.litreview.entity.Paper;
import org.thesis.research.litreview.entity.PaperRepository;
import org.thesis.research.litreview.service.IngestPipeline;

/**
 * Phase 1-3 control: load the library, parse PDFs, chunk and embed.
 *
 * <p>Every endpoint here is synchronous and can take minutes - a full run
 * parses 27 PDFs through GROBID and embeds several thousand chunks. That is
 * acceptable for a single-researcher tool driven from a terminal or a REST
 * client, and it keeps the run's outcome in the response rather than in a job
 * table. If this ever needs to serve concurrent users, these become job
 * submissions with a poll endpoint.
 */
@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private final IngestPipeline pipeline;
    private final PaperRepository papers;

    public IngestController(IngestPipeline pipeline, PaperRepository papers) {
        this.pipeline = pipeline;
        this.papers = papers;
    }

    /**
     * Ingests the whole library.
     *
     * @param force re-ingest papers that are already complete; without it a
     *              second call is cheap and changes nothing
     */
    @PostMapping("/run")
    public IngestPipeline.IngestSummary run(
            @RequestParam(name = "force", defaultValue = "false") boolean force) {
        return pipeline.runAll(force);
    }

    /** Re-ingests a single paper, e.g. after fixing its entry in library.bib. */
    @PostMapping("/{citekey}/reingest")
    public IngestPipeline.IngestSummary reingest(@PathVariable String citekey,
            @RequestParam(name = "force", defaultValue = "true") boolean force) {
        return pipeline.runOne(citekey, force);
    }

    /** Paper counts per ingest status - the progress view. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "byStatus", pipeline.statusReport(),
                "total", papers.count(),
                "failed", failedPapers());
    }

    /**
     * The papers that failed, with their reasons.
     *
     * <p>Separate from the counts because a failure is only actionable with its
     * message: "FAILED: 3" says nothing about whether to fix a bib entry or
     * give up on a scanned PDF.
     */
    @GetMapping("/failures")
    public List<Failure> failures() {
        return failedPapers();
    }

    private List<Failure> failedPapers() {
        return papers.findByStatus(Paper.IngestStatus.FAILED).stream()
                .map(p -> new Failure(p.getCitekey(), p.getTitle(), p.getFailureReason()))
                .toList();
    }

    /** A paper that could not be ingested. */
    public record Failure(String citekey, String title, String reason) {
    }
}
