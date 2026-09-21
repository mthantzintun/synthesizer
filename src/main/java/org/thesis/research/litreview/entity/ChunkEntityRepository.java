package org.thesis.research.litreview.entity;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Plain relational reads/writes on {@code chunk}. Embedding storage and the
 * hybrid similarity query live in {@link ChunkJdbcRepository} instead - see
 * that class and {@link ChunkEntity} for why.
 */
public interface ChunkEntityRepository extends JpaRepository<ChunkEntity, Long> {

    List<ChunkEntity> findByPaperIdOrderByOrdinal(Long paperId);

    /**
     * Loads chunks with their paper already fetched.
     *
     * <p>Search results are hydrated outside a transaction, so touching the
     * lazy {@code paper} association afterwards would throw
     * {@code LazyInitializationException} exactly when a result needs its
     * citekey. Fetching it in the same query also collapses what would
     * otherwise be one extra select per hit.
     */
    @EntityGraph(attributePaths = "paper")
    List<ChunkEntity> findByIdIn(Collection<Long> ids);

    long countByPaperId(Long paperId);

    void deleteByPaperId(Long paperId);
}
