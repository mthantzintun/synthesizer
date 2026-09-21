package org.thesis.research.litreview.entity;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Mirrors the {@code extraction} table: the Phase 4 structured concept-matrix
 * row for one paper. Every scalar field mirrors {@link
 * org.thesis.research.litreview.service.extraction.Extraction} - this entity
 * is the persisted twin of that record, kept separate so the LLM-facing
 * record can stay a plain, JPA-free record.
 */
@Entity
@Table(name = "extraction", uniqueConstraints = @UniqueConstraint(columnNames = "paper_id"))
public class ExtractionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "paper_id", nullable = false, unique = true)
    private Paper paper;

    @Column(name = "research_question", columnDefinition = "text")
    private String researchQuestion;

    @Column(name = "theoretical_lens", columnDefinition = "text")
    private String theoreticalLens;

    @Column(columnDefinition = "text")
    private String method;

    /** Postgres {@code text[]}. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> constructs;

    @Column(name = "key_finding", columnDefinition = "text")
    private String keyFinding;

    @Column(name = "stated_limitation", columnDefinition = "text")
    private String statedLimitation;

    @Column(name = "future_work_suggested", columnDefinition = "text")
    private String futureWorkSuggested;

    @Column(name = "context_sector", columnDefinition = "text")
    private String contextSector;

    @Column(name = "supporting_quote", columnDefinition = "text")
    private String supportingQuote;

    @Column(name = "quote_page")
    private String quotePage;

    @Column(name = "model_used")
    private String modelUsed;

    @Column(name = "extracted_at", nullable = false)
    private Instant extractedAt;

    protected ExtractionEntity() {
        // JPA
    }

    public ExtractionEntity(Paper paper) {
        this.paper = paper;
    }

    // -------------------------------------------------------- getters/setters

    public Long getId() {
        return id;
    }

    public Paper getPaper() {
        return paper;
    }

    public String getResearchQuestion() {
        return researchQuestion;
    }

    public void setResearchQuestion(String researchQuestion) {
        this.researchQuestion = researchQuestion;
    }

    public String getTheoreticalLens() {
        return theoreticalLens;
    }

    public void setTheoreticalLens(String theoreticalLens) {
        this.theoreticalLens = theoreticalLens;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public List<String> getConstructs() {
        return constructs;
    }

    public void setConstructs(List<String> constructs) {
        this.constructs = constructs;
    }

    public String getKeyFinding() {
        return keyFinding;
    }

    public void setKeyFinding(String keyFinding) {
        this.keyFinding = keyFinding;
    }

    public String getStatedLimitation() {
        return statedLimitation;
    }

    public void setStatedLimitation(String statedLimitation) {
        this.statedLimitation = statedLimitation;
    }

    public String getFutureWorkSuggested() {
        return futureWorkSuggested;
    }

    public void setFutureWorkSuggested(String futureWorkSuggested) {
        this.futureWorkSuggested = futureWorkSuggested;
    }

    public String getContextSector() {
        return contextSector;
    }

    public void setContextSector(String contextSector) {
        this.contextSector = contextSector;
    }

    public String getSupportingQuote() {
        return supportingQuote;
    }

    public void setSupportingQuote(String supportingQuote) {
        this.supportingQuote = supportingQuote;
    }

    public String getQuotePage() {
        return quotePage;
    }

    public void setQuotePage(String quotePage) {
        this.quotePage = quotePage;
    }

    public String getModelUsed() {
        return modelUsed;
    }

    public void setModelUsed(String modelUsed) {
        this.modelUsed = modelUsed;
    }

    public Instant getExtractedAt() {
        return extractedAt;
    }

    public void setExtractedAt(Instant extractedAt) {
        this.extractedAt = extractedAt;
    }
}
