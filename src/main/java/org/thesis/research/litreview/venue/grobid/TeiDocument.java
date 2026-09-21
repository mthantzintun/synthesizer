package org.thesis.research.litreview.venue.grobid;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A GROBID TEI document reduced to what the pipeline actually needs.
 *
 * @param title      title from the TEI header (GROBID's own parse, useful as a
 *                   cross-check against the BibTeX title)
 * @param authors    authors from the TEI header, {@code "Surname, I."} form
 * @param year       publication year GROBID found in the header, may be null
 * @param doi        DOI GROBID found in the header, may be null
 * @param abstractText the parsed abstract
 * @param sections   body divisions in document order
 */
public record TeiDocument(
        String title,
        List<String> authors,
        Integer year,
        String doi,
        String abstractText,
        List<Section> sections) {

    /**
     * One {@code <div>} of the TEI body.
     *
     * @param heading    the {@code <head>} text, e.g. {@code "3. Methodology"};
     *                   falls back to {@code "Body"} when GROBID found no heading
     * @param number     the {@code @n} attribute, e.g. {@code "3.1"}, may be null
     * @param paragraphs the {@code <p>} elements, each already sentence-joined
     */
    public record Section(String heading, String number, List<Paragraph> paragraphs) {

        public String text() {
            return paragraphs.stream().map(Paragraph::text).collect(java.util.stream.Collectors.joining("\n\n"));
        }

        public int charCount() {
            return paragraphs.stream().mapToInt(p -> p.text().length()).sum();
        }

        public boolean isEmpty() {
            return paragraphs.isEmpty() || charCount() == 0;
        }

        /**
         * Maps GROBID's free-text heading onto the canonical IMRaD slot used by
         * the gap grid. Headings in this corpus are wildly inconsistent
         * ("4. Results and Discussion", "Findings", "Empirical analysis"), so
         * the grid needs a normalized label to group on.
         */
        public CanonicalSection canonical() {
            return CanonicalSection.of(heading);
        }
    }

    /**
     * A paragraph plus the page it started on. The page number is what makes an
     * extracted quote verifiable - without it a reviewer cannot check the claim.
     *
     * @param text plain text, citation markers removed
     * @param page 1-based page number, or null when GROBID emitted no coordinates
     */
    public record Paragraph(String text, Integer page) {
    }

    /** Canonical IMRaD slots, plus the two that matter most for gap-hunting. */
    public enum CanonicalSection {
        ABSTRACT,
        INTRODUCTION,
        RELATED_WORK,
        THEORY,
        METHOD,
        RESULTS,
        DISCUSSION,
        LIMITATIONS,
        FUTURE_WORK,
        CONCLUSION,
        OTHER;

        public static CanonicalSection of(String heading) {
            if (heading == null || heading.isBlank()) {
                return OTHER;
            }
            String h = stripNumbering(heading);
            if (h.isBlank()) {
                return OTHER;
            }

            // order matters: "limitations and future research" must hit
            // FUTURE_WORK's sibling check before the broader DISCUSSION match
            if (h.contains("future") && (h.contains("work") || h.contains("research") || h.contains("direction"))) {
                return FUTURE_WORK;
            }
            if (h.contains("limitation")) {
                return LIMITATIONS;
            }
            if (h.startsWith("abstract")) {
                return ABSTRACT;
            }
            if (h.startsWith("introduction") || h.startsWith("background") || h.startsWith("motivation")) {
                return INTRODUCTION;
            }
            if (h.contains("literature review") || h.contains("related work")
                    || h.contains("state of the art") || h.contains("prior")) {
                return RELATED_WORK;
            }
            if (h.contains("theoretical") || h.contains("theory")
                    || h.contains("conceptual") || h.contains("hypothes")) {
                return THEORY;
            }
            if (h.contains("method") || h.contains("approach") || h.contains("research design")
                    || h.contains("data collection") || h.contains("model formulation")
                    || h.contains("materials")) {
                return METHOD;
            }
            if (h.contains("result") || h.contains("finding") || h.contains("analysis")
                    || h.contains("experiment") || h.contains("case stud")
                    || h.contains("evaluation") || h.contains("numerical")) {
                return RESULTS;
            }
            if (h.contains("discussion") || h.contains("implication")) {
                return DISCUSSION;
            }
            if (h.startsWith("conclusion") || h.startsWith("concluding") || h.startsWith("summary")) {
                return CONCLUSION;
            }
            return OTHER;
        }

        private static String stripNumbering(String heading) {
            // Lower-case first so the roman-numeral branch is case-insensitive.
            String h = heading.toLowerCase(Locale.ROOT);

            // "3.1. Method", "4) Results", "2 - Discussion": a leading number
            // (possibly dotted/roman) followed by a separator.
            String stripped = h.replaceFirst("^\\s*(\\d+(\\.\\d+)*|[ivxlc]+)\\s*[.):\\-\\u2013]\\s*", "");
            if (!stripped.equals(h)) {
                return stripped.trim();
            }

            // No separator, e.g. "3.1 Method". Require a following space so a
            // roman-numeral character at the start of a real word is not eaten -
            // the earlier version of this regex turned "introduction" into
            // "ntroduction" and mapped most of the corpus to OTHER.
            return h.replaceFirst("^\\s*(\\d+(\\.\\d+)*)\\s+", "").trim();
        }
    }

    // ------------------------------------------------------------- accessors

    /** Whole body as one string - the input to per-paper LLM extraction. */
    public String fullText() {
        StringBuilder sb = new StringBuilder();
        if (abstractText != null && !abstractText.isBlank()) {
            sb.append("Abstract\n").append(abstractText).append("\n\n");
        }
        for (Section s : sections) {
            sb.append(s.heading()).append('\n').append(s.text()).append("\n\n");
        }
        return sb.toString().trim();
    }

    public int charCount() {
        return fullText().length();
    }

    /** All text belonging to one canonical slot, concatenated. */
    public Optional<String> sectionText(CanonicalSection slot) {
        String text = sections.stream()
                .filter(s -> s.canonical() == slot)
                .map(Section::text)
                .collect(java.util.stream.Collectors.joining("\n\n"));
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    /**
     * True when GROBID produced so little body text that the parse should be
     * treated as failed - typically a scanned PDF with no text layer.
     */
    public boolean looksEmpty() {
        return sections.isEmpty() || charCount() < 500;
    }

    public String authorsJoined() {
        return authors == null || authors.isEmpty() ? null : String.join("; ", authors);
    }
}
