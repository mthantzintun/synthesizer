package org.thesis.research.litreview.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Mirrors the {@code paper} table. One row per BibTeX entry that survived
 * Phase 1 validation.
 */
@Entity
@Table(name = "paper", uniqueConstraints = @UniqueConstraint(columnNames = "citekey"))
public class Paper {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String citekey;

    private String doi;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String authors;

    private Integer year;

    private String venue;

    @Column(name = "pdf_path")
    private String pdfPath;

    // status is TEXT + CHECK in Postgres (not a native enum) - plain
    // EnumType.STRING mapping avoids needing an explicit ::ingest_status cast
    // on every parameter binding, which the plain postgresql JDBC driver
    // otherwise requires.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestStatus status = IngestStatus.PENDING;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Paper() {
        // JPA
    }

    public Paper(String citekey, String title, String authors) {
        this.citekey = citekey;
        this.title = title;
        this.authors = authors;
    }

    public static Paper of(String citekey, String doi, String title, String authors,
            Integer year, String venue, String pdfPath) {
        Paper p = new Paper(citekey, title, authors);
        p.doi = doi;
        p.year = year;
        p.venue = venue;
        p.pdfPath = pdfPath;
        return p;
    }

    public void markStatus(IngestStatus status) {
        this.status = status;
        if (status != IngestStatus.FAILED) {
            this.failureReason = null;
        }
    }

    public void markFailed(String reason) {
        this.status = IngestStatus.FAILED;
        this.failureReason = reason;
    }

    // ------------------------------------------------------------- getters

    public Long getId() {
        return id;
    }

    public String getCitekey() {
        return citekey;
    }

    public String getDoi() {
        return doi;
    }

    public void setDoi(String doi) {
        this.doi = doi;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthors() {
        return authors;
    }

    public void setAuthors(String authors) {
        this.authors = authors;
    }

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public String getVenue() {
        return venue;
    }

    public void setVenue(String venue) {
        this.venue = venue;
    }

    public String getPdfPath() {
        return pdfPath;
    }

    public void setPdfPath(String pdfPath) {
        this.pdfPath = pdfPath;
    }

    public IngestStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Mirrors the {@code ingest_status} Postgres enum. */
    public enum IngestStatus {
        PENDING, METADATA_OK, GROBID_OK, CHUNKED, EMBEDDED, EXTRACTED, FAILED
    }
}
