package org.thesis.research.litreview.entity;

import java.math.BigDecimal;
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
 * Mirrors the {@code api_call_log} table: one row per LLM call, so the cost of
 * a full corpus run is auditable after the fact.
 *
 * <p>Written best-effort - a failure to log must never fail the call it is
 * describing, so callers persist this in its own transaction and swallow
 * errors.
 */
@Entity
@Table(name = "api_call_log")
public class ApiCallLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paper_id")
    private Paper paper;

    @Column(name = "call_type", nullable = false)
    private String callType;

    @Column(nullable = false)
    private String model;

    @Column(name = "tokens_in")
    private Integer tokensIn;

    @Column(name = "tokens_out")
    private Integer tokensOut;

    @Column(name = "cost_usd")
    private BigDecimal costUsd;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "called_at", nullable = false, updatable = false, insertable = false)
    private Instant calledAt;

    protected ApiCallLogEntity() {
        // JPA
    }

    public ApiCallLogEntity(Paper paper, String callType, String model, boolean success) {
        this.paper = paper;
        this.callType = callType;
        this.model = model;
        this.success = success;
    }

    public static ApiCallLogEntity success(Paper paper, String callType, String model,
            Integer tokensIn, Integer tokensOut) {
        ApiCallLogEntity log = new ApiCallLogEntity(paper, callType, model, true);
        log.tokensIn = tokensIn;
        log.tokensOut = tokensOut;
        return log;
    }

    public static ApiCallLogEntity failure(Paper paper, String callType, String model, String errorMessage) {
        ApiCallLogEntity log = new ApiCallLogEntity(paper, callType, model, false);
        log.errorMessage = errorMessage;
        return log;
    }

    // ------------------------------------------------------------- getters

    public Long getId() {
        return id;
    }

    public Paper getPaper() {
        return paper;
    }

    public String getCallType() {
        return callType;
    }

    public String getModel() {
        return model;
    }

    public Integer getTokensIn() {
        return tokensIn;
    }

    public Integer getTokensOut() {
        return tokensOut;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCalledAt() {
        return calledAt;
    }
}
