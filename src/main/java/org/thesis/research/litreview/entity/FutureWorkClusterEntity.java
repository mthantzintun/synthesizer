package org.thesis.research.litreview.entity;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Mirrors the {@code future_work_cluster} table: a theme raised as "future
 * work" by two or more papers, found by {@code FutureWorkMiner}.
 *
 * <p>{@code paperCount} is denormalized (kept in sync with
 * {@code paperIds.size()} by the miner) purely so {@code ORDER BY paper_count
 * DESC} can use the plain btree index instead of computing array length on
 * every row.
 */
@Entity
@Table(name = "future_work_cluster")
public class FutureWorkClusterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "text")
    private String theme;

    /** Postgres {@code bigint[]} - the paper ids that raised this theme. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "paper_ids", nullable = false, columnDefinition = "bigint[]")
    private List<Long> paperIds;

    @Column(name = "paper_count", nullable = false)
    private int paperCount;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected FutureWorkClusterEntity() {
        // JPA
    }

    public FutureWorkClusterEntity(String theme, List<Long> paperIds) {
        this.theme = theme;
        this.paperIds = paperIds;
        this.paperCount = paperIds.size();
    }

    // ------------------------------------------------------------- getters

    public Long getId() {
        return id;
    }

    public String getTheme() {
        return theme;
    }

    public List<Long> getPaperIds() {
        return paperIds;
    }

    public int getPaperCount() {
        return paperCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
