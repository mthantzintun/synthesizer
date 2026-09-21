package org.thesis.research.litreview.util;

import org.thesis.research.litreview.venue.grobid.TeiDocument;

/**
 * One embeddable unit of text.
 *
 * @param citekey   owning paper
 * @param section   the paper's own heading, e.g. {@code "3.2 Simulation setup"}
 * @param canonical normalized IMRaD slot, used for filtering and the gap grid
 * @param ordinal   0-based position of this chunk within the whole paper
 * @param page      page the chunk starts on, null when GROBID gave no coordinates
 * @param text      raw chunk text, without the heading prefix
 */
public record Chunk(
        String citekey,
        String section,
        TeiDocument.CanonicalSection canonical,
        int ordinal,
        Integer page,
        String text) {

    /**
     * What actually gets embedded. Prefixing the heading gives the embedding
     * model the context a bare paragraph lacks - "we found no significant
     * effect" means something very different under "Results" than under
     * "Literature Review", and without the prefix both embed almost
     * identically.
     */
    public String embeddingText() {
        return "[%s] %s".formatted(section, text);
    }

    public int length() {
        return text.length();
    }
}
