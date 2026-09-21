package org.thesis.research.litreview.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.thesis.research.litreview.entity.ExtractionEntity;
import org.thesis.research.litreview.service.extraction.ExtractionService;

/**
 * Phase 4 control: structured concept-matrix extraction per paper.
 *
 * <p>Exposes the extraction rows directly rather than through a DTO. Unlike
 * search results, the concept matrix <em>is</em> the output - every field is
 * something the researcher reads and cites - so a projection layer would only
 * add a place for the two shapes to drift apart.
 */
@RestController
@RequestMapping("/api/extraction")
public class ExtractionController {

    private final ExtractionService extractionService;

    public ExtractionController(ExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    /**
     * Extracts every paper that is embedded but not yet extracted.
     *
     * @return the rows written, with a count
     */
    @PostMapping("/run")
    public Map<String, Object> run() {
        List<ExtractionEntity> done = extractionService.extractAllPending();
        return Map.of("extracted", done.size(), "papers", done.stream()
                .map(e -> e.getPaper().getCitekey())
                .toList());
    }

    /** Re-extracts one paper, e.g. after a model or prompt change. */
    @PostMapping("/{citekey}/run")
    public ExtractionEntity runOne(@PathVariable String citekey) {
        return extractionService.extractByCitekey(citekey);
    }

    /** The concept-matrix row for one paper. */
    @GetMapping("/{citekey}")
    public ResponseEntity<ExtractionEntity> get(@PathVariable String citekey) {
        return extractionService.find(citekey)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Every extraction in the corpus - the raw matrix. */
    @GetMapping
    public List<ExtractionEntity> all(
            @RequestParam(name = "construct", required = false) String construct) {
        if (construct == null || construct.isBlank()) {
            return extractionService.findAll();
        }
        return extractionService.findByConstruct(construct);
    }
}
