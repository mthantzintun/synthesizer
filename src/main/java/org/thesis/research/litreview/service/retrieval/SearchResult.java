package org.thesis.research.litreview.service.retrieval;

import org.thesis.research.litreview.entity.ChunkEntity;
import org.thesis.research.litreview.venue.grobid.TeiDocument;

/**
 * One retrieved chunk, ready to be cited.
 *
 * <p>Carries {@code citekey} and {@code page} rather than the raw foreign key
 * because every consumer of a result is building prose that has to point back
 * at a specific place in a specific paper. A chunk id is useless for that.
 *
 * @param chunkId          {@code chunk.id}, for follow-up fetches
 * @param paperId          {@code paper.id}
 * @param citekey          the citation handle, e.g. {@code RN63}
 * @param title            paper title, for display
 * @param section          the paper's own heading, e.g. {@code "4.2 Results"}
 * @param canonicalSection normalized IMRaD slot, may be null
 * @param page             1-based page, may be null when GROBID gave no coordinates
 * @param content          the chunk text that was retrieved
 * @param score            fused RRF score; only meaningful for ordering, not comparable across queries
 */
public record SearchResult(
        Long chunkId,
        Long paperId,
        String citekey,
        String title,
        String section,
        TeiDocument.CanonicalSection canonicalSection,
        Integer page,
        String content,
        double score) {

    public static SearchResult from(ChunkEntity chunk, double score) {
        return new SearchResult(
                chunk.getId(),
                chunk.getPaper().getId(),
                chunk.getPaper().getCitekey(),
                chunk.getPaper().getTitle(),
                chunk.getSection(),
                chunk.getCanonicalSection(),
                chunk.getPage(),
                chunk.getContent(),
                score);
    }

    /** {@code [RN63 p.7]}-style marker, for grounding an LLM prompt. */
    public String citationMarker() {
        return page == null ? "[%s]".formatted(citekey) : "[%s p.%d]".formatted(citekey, page);
    }
}
