package org.thesis.research.litreview.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.thesis.research.litreview.config.LitreviewProperties;
import org.thesis.research.litreview.util.Chunk;
import org.thesis.research.litreview.venue.grobid.TeiDocument;

/**
 * TeiDocument -&gt; List&lt;Chunk&gt;, section-aware and heading-prefixed.
 *
 * <p>Strategy: never split a section across a paragraph boundary unless the
 * section itself is longer than {@code maxChars}. Paragraphs are packed
 * greedily up to {@code targetChars}; when a single section overflows
 * {@code maxChars} it is split on sentence boundaries with a character
 * overlap so a claim that straddles a split boundary is not lost.
 *
 * <p>Sections in {@code litreview.chunking.skip-sections} (references,
 * acknowledgements, ...) are dropped entirely - they carry no synthesizable
 * signal and would only dilute retrieval.
 */
@Service
public class ChunkingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

    /** Split point for overflow sections: end of a sentence. */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+");

    private final LitreviewProperties.Chunking config;

    public ChunkingService(LitreviewProperties props) {
        this.config = props.chunking();
    }

    public List<Chunk> chunk(String citekey, TeiDocument doc) {
        List<Chunk> chunks = new ArrayList<>();
        int ordinal = 0;

        // The abstract is short and dense - always its own chunk when present.
        if (doc.abstractText() != null && !doc.abstractText().isBlank()) {
            chunks.add(new Chunk(citekey, "Abstract", TeiDocument.CanonicalSection.ABSTRACT,
                    ordinal++, null, doc.abstractText()));
        }

        for (TeiDocument.Section section : doc.sections()) {
            if (section.isEmpty() || config.isSkippable(section.heading())) {
                continue;
            }

            List<Chunk> sectionChunks = chunkSection(citekey, section, ordinal);
            chunks.addAll(sectionChunks);
            ordinal += sectionChunks.size();
        }

        log.debug("{}: {} chunks from {} sections", citekey, chunks.size(), doc.sections().size());
        return chunks;
    }

    private List<Chunk> chunkSection(String citekey, TeiDocument.Section section, int startOrdinal) {
        List<Chunk> out = new ArrayList<>();
        TeiDocument.CanonicalSection canonical = section.canonical();

        StringBuilder buffer = new StringBuilder();
        Integer bufferPage = null;
        int ordinal = startOrdinal;

        for (TeiDocument.Paragraph para : section.paragraphs()) {
            String text = para.text();
            if (text.isBlank()) {
                continue;
            }

            // A single paragraph longer than maxChars must be split on its own,
            // regardless of what is currently buffered.
            if (text.length() > config.maxChars()) {
                if (buffer.length() > 0) {
                    out.add(toChunk(citekey, section, canonical, ordinal++, bufferPage, buffer.toString()));
                    buffer.setLength(0);
                    bufferPage = null;
                }
                for (String piece : splitLong(text)) {
                    out.add(toChunk(citekey, section, canonical, ordinal++, para.page(), piece));
                }
                continue;
            }

            boolean wouldOverflow = buffer.length() + text.length() + 2 > config.maxChars();
            boolean bufferBigEnough = buffer.length() >= config.targetChars();

            if (buffer.length() > 0 && (wouldOverflow || bufferBigEnough)) {
                out.add(toChunk(citekey, section, canonical, ordinal++, bufferPage, buffer.toString()));
                buffer.setLength(0);
                bufferPage = null;
            }

            if (buffer.length() == 0) {
                bufferPage = para.page();
            }
            else {
                buffer.append("\n\n");
            }
            buffer.append(text);
        }

        if (buffer.length() > 0) {
            // A trailing fragment shorter than minChars is still kept - dropping
            // the tail of a section (often the conclusion of that subsection)
            // would lose real content for a rare edge case.
            out.add(toChunk(citekey, section, canonical, ordinal++, bufferPage, buffer.toString()));
        }

        return out;
    }

    /** Splits a single oversized paragraph on sentence boundaries with overlap. */
    private List<String> splitLong(String text) {
        List<String> pieces = new ArrayList<>();
        String[] sentences = SENTENCE_BOUNDARY.split(text);

        StringBuilder buffer = new StringBuilder();
        for (String sentence : sentences) {
            if (buffer.length() + sentence.length() + 1 > config.maxChars() && buffer.length() > 0) {
                pieces.add(buffer.toString().trim());
                // carry the tail of the previous piece forward as overlap context
                String tail = tail(buffer.toString(), config.overlapChars());
                buffer.setLength(0);
                buffer.append(tail);
            }
            if (buffer.length() > 0) {
                buffer.append(' ');
            }
            buffer.append(sentence);

            // A single sentence longer than maxChars (rare - a giant table row,
            // a run-on formula) is hard-cut rather than left whole.
            while (buffer.length() > config.maxChars()) {
                pieces.add(buffer.substring(0, config.maxChars()).trim());
                buffer.delete(0, config.maxChars() - config.overlapChars());
            }
        }
        if (buffer.length() > 0) {
            pieces.add(buffer.toString().trim());
        }
        return pieces;
    }

    private String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    private Chunk toChunk(String citekey, TeiDocument.Section section, TeiDocument.CanonicalSection canonical,
            int ordinal, Integer page, String text) {
        String heading = section.number() != null
                ? section.number() + " " + section.heading()
                : section.heading();
        return new Chunk(citekey, heading, canonical, ordinal, page, text.trim());
    }
}
