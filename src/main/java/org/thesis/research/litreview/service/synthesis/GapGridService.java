package org.thesis.research.litreview.service.synthesis;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.thesis.research.litreview.dto.CoverageCell;
import org.thesis.research.litreview.entity.ChunkJdbcRepository;
import org.thesis.research.litreview.entity.ChunkJdbcRepository.CoverageRow;
import org.thesis.research.litreview.service.extraction.ExtractionRepository;
import org.thesis.research.litreview.venue.grobid.TeiDocument;

/**
 * Builds the coverage grid that makes gaps visible.
 *
 * <p>A gap is an absence, and absences are hard to read off a corpus. The grid
 * turns "nobody has tested this" into a number: for each construct the corpus
 * names, how many papers address it in each canonical section.
 *
 * <p>The section axis is what makes this more than a paper count. A construct
 * that appears in eleven introductions and no results section is not
 * under-studied - it is asserted and untested, which is a much more specific
 * and much more defensible claim. A construct with no cells at all is a
 * different finding again.
 *
 * <p>Entirely deterministic: no LLM call, so the grid can be regenerated
 * freely and cited without hedging.
 */
@Service
public class GapGridService {

    private static final Logger log = LoggerFactory.getLogger(GapGridService.class);

    /**
     * Sections worth gridding. Excludes ABSTRACT and OTHER: an abstract
     * mentions almost everything by construction, and OTHER is a bucket, not a
     * section - including either would add noise to every row.
     */
    private static final List<TeiDocument.CanonicalSection> GRID_SECTIONS = List.of(
            TeiDocument.CanonicalSection.INTRODUCTION,
            TeiDocument.CanonicalSection.RELATED_WORK,
            TeiDocument.CanonicalSection.THEORY,
            TeiDocument.CanonicalSection.METHOD,
            TeiDocument.CanonicalSection.RESULTS,
            TeiDocument.CanonicalSection.DISCUSSION,
            TeiDocument.CanonicalSection.LIMITATIONS,
            TeiDocument.CanonicalSection.FUTURE_WORK,
            TeiDocument.CanonicalSection.CONCLUSION);

    private final ExtractionRepository extractions;
    private final ChunkJdbcRepository chunkJdbc;

    public GapGridService(ExtractionRepository extractions, ChunkJdbcRepository chunkJdbc) {
        this.extractions = extractions;
        this.chunkJdbc = chunkJdbc;
    }

    /**
     * Builds a cell for every (construct, section) pair, including empty ones.
     *
     * <p>Empty cells are the point - a grid that only lists what exists cannot
     * show what does not. The caller filters on {@code coverageCount == 0} to
     * get the gaps.
     */
    public List<CoverageCell> buildGrid() {
        List<String> constructs = extractions.findAllDistinctConstructs();
        log.info("Gap grid: {} distinct construct(s) across {} section(s)",
                constructs.size(), GRID_SECTIONS.size());

        List<CoverageCell> cells = new ArrayList<>();
        for (String construct : constructs) {
            List<CoverageRow> covered = chunkJdbc.constructCoverage(construct);
            for (TeiDocument.CanonicalSection section : GRID_SECTIONS) {
                cells.add(covered.stream()
                        .filter(row -> section.name().equals(row.canonicalSection()))
                        .findFirst()
                        .map(row -> CoverageCell.of(construct, section.name(), row.citekeys()))
                        .orElseGet(() -> CoverageCell.of(construct, section.name(), List.of())));
            }
        }
        return cells;
    }

    /**
     * The cells with no coverage - the gaps themselves.
     *
     * <p>Ordered by construct then section so the output reads as a stable
     * report rather than a set.
     */
    public List<CoverageCell> uncovered() {
        return buildGrid().stream()
                .filter(cell -> cell.coverageCount() == 0)
                .toList();
    }

    /**
     * Constructs no paper addresses in a RESULTS or DISCUSSION section.
     *
     * <p>The narrow, interesting question: constructs the literature talks
     * about but does not empirically examine.
     */
    public List<String> assertedButUntested() {
        return buildGrid().stream()
                .filter(cell -> cell.coverageCount() == 0)
                .filter(cell -> TeiDocument.CanonicalSection.RESULTS.name().equals(cell.canonicalSection())
                        || TeiDocument.CanonicalSection.DISCUSSION.name().equals(cell.canonicalSection()))
                .map(CoverageCell::construct)
                .distinct()
                .sorted()
                .toList();
    }
}
