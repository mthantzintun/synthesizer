package org.thesis.research.litreview.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thesis.research.litreview.dto.Contradiction;
import org.thesis.research.litreview.dto.CoverageCell;
import org.thesis.research.litreview.dto.FutureWorkCluster;
import org.thesis.research.litreview.service.paper.GapSteelmanService;
import org.thesis.research.litreview.service.synthesis.ContradictionFinder;
import org.thesis.research.litreview.service.synthesis.FutureWorkMiner;
import org.thesis.research.litreview.service.synthesis.GapGridService;

/**
 * Phase 5-6 control: cross-paper synthesis and gap analysis.
 *
 * <p>Split by output rather than by phase, because the four outputs have very
 * different cost and trust profiles: the gap grid is a deterministic SQL
 * aggregate, contradictions and future-work clusters are derived from
 * extraction rows, and only the steelman calls a model live. Keeping them on
 * separate paths means the cheap, citable views stay available even when the
 * LLM endpoints are not configured.
 */
@RestController
@RequestMapping("/api/synthesis")
public class SynthesisController {

    private final ContradictionFinder contradictions;
    private final FutureWorkMiner futureWork;
    private final GapGridService gapGrid;
    private final GapSteelmanService steelman;

    public SynthesisController(ContradictionFinder contradictions, FutureWorkMiner futureWork,
            GapGridService gapGrid, GapSteelmanService steelman) {
        this.contradictions = contradictions;
        this.futureWork = futureWork;
        this.gapGrid = gapGrid;
        this.steelman = steelman;
    }

    // ------------------------------------------------------- contradictions

    /**
     * Scans the corpus for new contradictions.
     *
     * <p>Additive: existing findings - and the human verdicts attached to them -
     * are left alone. Only genuinely new pairs are written.
     */
    @PostMapping("/contradictions/run")
    public Map<String, Object> findContradictions() {
        List<Contradiction> found = contradictions.find().stream()
                .map(Contradiction::from)
                .toList();
        return Map.of("new", found.size(), "contradictions", found);
    }

    @GetMapping("/contradictions")
    public List<Contradiction> listContradictions() {
        return contradictions.findAll().stream().map(Contradiction::from).toList();
    }

    /** Findings nobody has adjudicated yet - the review queue. */
    @GetMapping("/contradictions/unreviewed")
    public List<Contradiction> unreviewedContradictions() {
        return contradictions.findUnreviewed().stream().map(Contradiction::from).toList();
    }

    /**
     * Records a verdict on a finding.
     *
     * <p>This is the only write that can touch an existing contradiction, and
     * it exists because the review flag is the reason contradictions are
     * persisted at all.
     */
    @PostMapping("/contradictions/{id}/review")
    public Contradiction reviewContradiction(@PathVariable Long id,
            @RequestBody ReviewRequest request) {
        return Contradiction.from(contradictions.review(id, request.verdict()));
    }

    // ---------------------------------------------------------- future work

    /** Rebuilds the future-work clusters from the current extractions. */
    @PostMapping("/future-work/run")
    public List<FutureWorkCluster> mineFutureWork() {
        futureWork.mine();
        return futureWork.asDtos();
    }

    @GetMapping("/future-work")
    public List<FutureWorkCluster> listFutureWork() {
        return futureWork.asDtos();
    }

    // -------------------------------------------------------------- gap grid

    /** The full coverage grid, including empty cells. */
    @GetMapping("/gap-grid")
    public List<CoverageCell> gapGrid() {
        return gapGrid.buildGrid();
    }

    /** Only the cells with no coverage. */
    @GetMapping("/gap-grid/uncovered")
    public List<CoverageCell> uncovered() {
        return gapGrid.uncovered();
    }

    /** Constructs discussed but never tested - no RESULTS or DISCUSSION coverage. */
    @GetMapping("/gap-grid/asserted-but-untested")
    public List<String> assertedButUntested() {
        return gapGrid.assertedButUntested();
    }

    // -------------------------------------------------------------- steelman

    /**
     * Attacks a proposed gap statement with the strongest corpus-grounded
     * counter-argument it can find.
     */
    @PostMapping("/steelman")
    public GapSteelmanService.Steelman steelman(@RequestBody SteelmanRequest request) {
        return steelman.steelman(request.gap());
    }

    /** A human verdict on a contradiction. */
    public record ReviewRequest(String verdict) {
    }

    /** A proposed gap to attack. */
    public record SteelmanRequest(String gap) {
    }
}
