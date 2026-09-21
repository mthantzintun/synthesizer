package org.thesis.research.litreview.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Mirrors the {@code contradiction} table: a persisted disagreement between
 * two papers on the same construct, found by {@code ContradictionFinder}.
 *
 * <p>Persisted rather than recomputed on every request because
 * {@code reviewed}/{@code verdict} are a human's judgement call recorded
 * against a specific finding - re-running the finder must not lose that.
 */
@Entity
@Table(name = "contradiction")
public class ContradictionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "text")
    private String construct;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "paper_a_id", nullable = false)
    private Paper paperA;

    @Column(name = "claim_a", nullable = false, columnDefinition = "text")
    private String claimA;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "paper_b_id", nullable = false)
    private Paper paperB;

    @Column(name = "claim_b", nullable = false, columnDefinition = "text")
    private String claimB;

    @Column(name = "possible_moderator", columnDefinition = "text")
    private String possibleModerator;

    @Column(nullable = false)
    private boolean reviewed = false;

    @Column(columnDefinition = "text")
    private String verdict;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected ContradictionEntity() {
        // JPA
    }

    public ContradictionEntity(String construct, Paper paperA, String claimA,
            Paper paperB, String claimB, String possibleModerator) {
        this.construct = construct;
        this.paperA = paperA;
        this.claimA = claimA;
        this.paperB = paperB;
        this.claimB = claimB;
        this.possibleModerator = possibleModerator;
    }

    public void review(String verdict) {
        this.reviewed = true;
        this.verdict = verdict;
    }

    // ------------------------------------------------------------- getters

    public Long getId() {
        return id;
    }

    public String getConstruct() {
        return construct;
    }

    public Paper getPaperA() {
        return paperA;
    }

    public String getClaimA() {
        return claimA;
    }

    public Paper getPaperB() {
        return paperB;
    }

    public String getClaimB() {
        return claimB;
    }

    public String getPossibleModerator() {
        return possibleModerator;
    }

    public boolean isReviewed() {
        return reviewed;
    }

    public String getVerdict() {
        return verdict;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
