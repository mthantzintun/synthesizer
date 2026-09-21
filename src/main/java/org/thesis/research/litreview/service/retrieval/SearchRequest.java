package org.thesis.research.litreview.service.retrieval;

import java.util.List;

import org.thesis.research.litreview.venue.grobid.TeiDocument;

/**
 * A retrieval query, optionally narrowed to a canonical section or a set of
 * papers.
 *
 * <p>Filters exist because the two consumers want different things: the search
 * endpoint is open-ended, while the gap-grid and steelman flows ask
 * deliberately narrow questions ("what does this corpus claim about
 * <em>visibility</em> in RESULTS sections only?"). Pushing the filter into SQL
 * keeps the vector arm from spending its {@code candidatePool} on rows that
 * will be discarded.
 *
 * @param query           free-text query; must not be blank
 * @param canonicalSection restrict to one IMRaD slot, or null for all
 * @param paperIds        restrict to these papers, or null/empty for all
 * @param topK            results to return, or null to use the configured default
 */
public record SearchRequest(
        String query,
        TeiDocument.CanonicalSection canonicalSection,
        List<Long> paperIds,
        Integer topK) {

    public static SearchRequest of(String query) {
        return new SearchRequest(query, null, null, null);
    }

    public static SearchRequest of(String query, int topK) {
        return new SearchRequest(query, null, null, topK);
    }

    /** Canonical-section filter as the list the repository expects. */
    public List<TeiDocument.CanonicalSection> sections() {
        return canonicalSection == null ? List.of() : List.of(canonicalSection);
    }

    public boolean hasQuery() {
        return query != null && !query.isBlank();
    }
}
