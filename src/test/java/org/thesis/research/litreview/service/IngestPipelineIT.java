package org.thesis.research.litreview.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.thesis.research.litreview.entity.ChunkEntityRepository;
import org.thesis.research.litreview.entity.ChunkJdbcRepository;
import org.thesis.research.litreview.entity.Paper;
import org.thesis.research.litreview.entity.PaperRepository;
import org.thesis.research.litreview.service.retrieval.HybridSearchService;
import org.thesis.research.litreview.service.retrieval.SearchResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end test of the ingest &rarr; embed &rarr; retrieve path against a real
 * Postgres (pgvector) and a real GROBID.
 *
 * <p>Disabled by default and run manually:
 * <pre>
 *   docker compose up -d
 *   ./mvnw test -Dtest=IngestPipelineIT -DfailIfNoTests=false
 * </pre>
 *
 * <p>This is the only place the two things that unit tests cannot reach are
 * exercised: that a {@code float[]} actually round-trips through the pgvector
 * JDBC type, and that the fused SQL returns rows in a sane order. Both fail
 * loudly and confusingly in production if they are wrong, so they are worth an
 * explicit test even though it cannot run in CI without Docker.
 */
@SpringBootTest
@Disabled("needs a running Postgres + GROBID; run manually with docker compose up")
@TestPropertySource(properties = {
        "litreview.pdf-root=src/main/resources/papers",
        "litreview.bib-file=src/main/resources/data/library.bib"
})
class IngestPipelineIT {

    private static final Path BIB = Path.of("src/main/resources/data/library.bib");

    @Autowired
    private IngestPipeline pipeline;

    @Autowired
    private HybridSearchService search;

    @Autowired
    private PaperRepository papers;

    @Autowired
    private ChunkEntityRepository chunks;

    @Autowired
    private ChunkJdbcRepository chunkJdbc;

    @Test
    void ingestsTheLibraryAndMakesItSearchable() {
        assumeTrue(Files.isReadable(BIB), "library.bib not present");

        IngestPipeline.IngestSummary summary = pipeline.runAll(false);
        assertThat(summary.ingested()).isGreaterThan(0);

        List<Paper> embedded = papers.findByStatus(Paper.IngestStatus.EMBEDDED);
        assertThat(embedded).isNotEmpty();

        // every embedded paper must have chunks, and every chunk an embedding -
        // a chunk without a vector is invisible to the vector arm
        for (Paper paper : embedded) {
            long count = chunks.countByPaperId(paper.getId());
            assertThat(count).as("chunks for %s", paper.getCitekey()).isGreaterThan(0);
        }
    }

    @Test
    void hybridSearchReturnsCitedResultsForATopicalQuery() {
        List<SearchResult> results = search.search("last-mile delivery emissions", 10);

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(r -> {
            assertThat(r.citekey()).isNotBlank();
            assertThat(r.content()).isNotBlank();
            assertThat(r.chunkId()).isNotNull();
        });
        // scores must be descending - the SQL orders by them
        assertThat(results).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));
    }

    @Test
    void blankQueryReturnsNothingRatherThanEverything() {
        assertThat(search.search("   ", 10)).isEmpty();
    }

    @Test
    void reingestReplacesChunksRatherThanDuplicatingThem() {
        Paper paper = papers.findByStatus(Paper.IngestStatus.EMBEDDED).stream().findFirst().orElseThrow();
        long before = chunks.countByPaperId(paper.getId());

        pipeline.runOne(paper.getCitekey(), true);

        // delete-then-insert must be idempotent: a duplicated generation would
        // double every downstream count and return near-duplicate evidence
        assertThat(chunks.countByPaperId(paper.getId())).isEqualTo(before);
    }

    @Test
    void constructCoverageIsQueryable() {
        // A term that certainly appears somewhere in a logistics corpus. The
        // point is that the query runs against the ILIKE + canonical_section
        // path and returns section-labelled rows, not that it finds a number.
        List<ChunkJdbcRepository.CoverageRow> rows = chunkJdbc.constructCoverage("logistics");

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.canonicalSection()).isNotBlank();
            assertThat(row.paperCount()).isGreaterThan(0);
            assertThat(row.citekeys()).isNotEmpty();
        });
    }
}
