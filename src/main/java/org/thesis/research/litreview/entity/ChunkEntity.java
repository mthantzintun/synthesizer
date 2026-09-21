package org.thesis.research.litreview.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.thesis.research.litreview.venue.grobid.TeiDocument;

/**
 * Mirrors the relational columns of the {@code chunk} table: one embeddable,
 * section-aware text unit.
 *
 * <p>{@code embedding} (pgvector) and {@code tsv} (generated tsvector) are
 * deliberately <em>not</em> mapped here - Hibernate has no reliable built-in
 * pgvector type, and the generated tsvector column must never be written by
 * the ORM. Both are handled with plain JDBC in {@code ChunkJdbcRepository},
 * which owns writing embeddings and running the hybrid similarity query.
 * This entity exists so JPA relations (paper &rarr; chunks) and simple reads
 * stay ordinary Spring Data.
 */
@Entity
@Table(name = "chunk")
public class ChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "paper_id", nullable = false)
    private Paper paper;

    /** The paper's own heading text, e.g. {@code "3.2 Simulation setup"}. */
    private String section;

    @Enumerated(EnumType.STRING)
    @Column(name = "canonical_section")
    private TeiDocument.CanonicalSection canonicalSection;

    @Column(nullable = false)
    private int ordinal;

    private Integer page;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected ChunkEntity() {
        // JPA
    }

    public ChunkEntity(Paper paper, String section, TeiDocument.CanonicalSection canonicalSection,
            int ordinal, Integer page, String content) {
        this.paper = paper;
        this.section = section;
        this.canonicalSection = canonicalSection;
        this.ordinal = ordinal;
        this.page = page;
        this.content = content;
    }

    // ------------------------------------------------------------- getters

    public Long getId() {
        return id;
    }

    public Paper getPaper() {
        return paper;
    }

    public String getSection() {
        return section;
    }

    public TeiDocument.CanonicalSection getCanonicalSection() {
        return canonicalSection;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public Integer getPage() {
        return page;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
