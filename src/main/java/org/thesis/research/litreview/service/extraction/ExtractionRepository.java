package org.thesis.research.litreview.service.extraction;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.thesis.research.litreview.entity.ExtractionEntity;

/**
 * JPA access to the {@code extraction} table.
 *
 * <p>Named {@code ExtractionRepository} after the service-layer package it
 * serves; the entity it manages is {@link ExtractionEntity}. The distinction
 * matters because {@link Extraction} (the record) is the LLM's output type and
 * must never be persisted directly.
 *
 * <p>Every read method fetches the owning {@code paper} eagerly. Extraction
 * rows are read outside a transaction (they are serialized straight to JSON by
 * the controller, and grouped by the synthesis services), so touching the lazy
 * association afterwards would throw {@code LazyInitializationException} - and
 * every consumer needs the citekey anyway.
 */
public interface ExtractionRepository extends JpaRepository<ExtractionEntity, Long> {

    @Override
    @EntityGraph(attributePaths = "paper")
    List<ExtractionEntity> findAll();

    @EntityGraph(attributePaths = "paper")
    Optional<ExtractionEntity> findByPaperId(Long paperId);

    @EntityGraph(attributePaths = "paper")
    Optional<ExtractionEntity> findByPaperCitekey(String citekey);

    boolean existsByPaperId(Long paperId);

    /**
     * Papers whose extraction names at least one of the given constructs.
     *
     * <p>Uses Postgres array overlap ({@code &&}) against the GIN index on
     * {@code constructs}, so this stays a single indexed lookup rather than a
     * full scan with a per-row list intersection in Java.
     */
    @EntityGraph(attributePaths = "paper")
    @Query(value = """
            SELECT * FROM extraction
            WHERE constructs && CAST(:constructs AS text[])
            """, nativeQuery = true)
    List<ExtractionEntity> findByAnyConstruct(@Param("constructs") String[] constructs);

    /** Every distinct construct in the corpus, for the gap grid. */
    @Query(value = "SELECT DISTINCT unnest(constructs) FROM extraction ORDER BY 1", nativeQuery = true)
    List<String> findAllDistinctConstructs();
}
