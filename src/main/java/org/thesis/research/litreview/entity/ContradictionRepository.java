package org.thesis.research.litreview.entity;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * JPA access to the {@code contradiction} table.
 *
 * <p>Both papers are fetched eagerly on every read: a contradiction is only
 * meaningful with the two citekeys that make it up, and the DTO layer resolves
 * them outside a transaction.
 */
public interface ContradictionRepository extends JpaRepository<ContradictionEntity, Long> {

    @Override
    @EntityGraph(attributePaths = { "paperA", "paperB" })
    List<ContradictionEntity> findAll();

    @EntityGraph(attributePaths = { "paperA", "paperB" })
    List<ContradictionEntity> findByConstructIgnoreCase(String construct);

    @EntityGraph(attributePaths = { "paperA", "paperB" })
    List<ContradictionEntity> findByReviewedFalse();
}
