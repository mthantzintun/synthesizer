package org.thesis.research.litreview.ingest.bibtex;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One BibTeX entry, normalized and resolved against the local PDF store.
 *
 * @param citekey     BibTeX key, e.g. {@code RN63}. Unique per library.
 * @param entryType   {@code article}, {@code book}, {@code inbook}, ...
 * @param title       cleaned title (LaTeX decoded, braces stripped)
 * @param authors     normalized as {@code "Surname, I.; Surname, I."}
 * @param year        publication year, null when the entry omits it
 * @param venue       journal, booktitle or publisher - whichever is present
 * @param doi         bare DOI (any {@code https://doi.org/} prefix removed)
 * @param abstractText the {@code abstract} field, if any
 * @param declaredFile the raw filename taken from the {@code file = {...}} field
 * @param pdfPath     resolved absolute path to the PDF, or null when unresolved
 */
public record PaperMeta(
        String citekey,
        String entryType,
        String title,
        String authors,
        Integer year,
        String venue,
        String doi,
        String abstractText,
        String declaredFile,
        Path pdfPath) {

    /** @return a copy with {@code pdfPath} replaced - used by the path-resolution step. */
    public PaperMeta withPdfPath(Path resolved) {
        return new PaperMeta(citekey, entryType, title, authors, year, venue,
                doi, abstractText, declaredFile, resolved);
    }

    public boolean hasPdf() {
        return pdfPath != null;
    }

    /** First author's surname, for display: {@code "Bachofner et al. (2022)"}. */
    public String shortCitation() {
        String surname = "Anon";
        if (authors != null && !authors.isBlank()) {
            String first = authors.split(";")[0].trim();
            surname = first.contains(",") ? first.substring(0, first.indexOf(',')).trim() : first;
        }
        boolean multiple = authors != null && authors.contains(";");
        return surname + (multiple ? " et al." : "") + " (" + (year == null ? "n.d." : year) + ")";
    }

    /**
     * Blocking problems. A paper with any of these cannot be ingested at all:
     * there is either nothing to cite it by, or no text to read.
     *
     * @return list of human-readable problems; empty means ingestable
     */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (citekey == null || citekey.isBlank()) {
            problems.add("missing citekey");
        }
        if (title == null || title.isBlank()) {
            problems.add("missing title");
        }
        if (declaredFile == null || declaredFile.isBlank()) {
            problems.add("no file attachment declared in bib entry");
        }
        else if (pdfPath == null) {
            problems.add("declared PDF not found under pdf-root: " + declaredFile);
        }
        return problems;
    }

    /**
     * Non-blocking data-quality issues. These degrade the synthesis (a paper
     * with no year cannot appear in a chronological gap grid) but must not
     * exclude the paper - the full text is still worth extracting, and the
     * missing field can be back-filled from GROBID's header parse or the DOI.
     *
     * @return list of human-readable warnings; empty means clean
     */
    public List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        if (authors == null || authors.isBlank()) {
            warnings.add("missing author");
        }
        if (year == null) {
            warnings.add("missing year (excluded from year-based views)");
        }
        else if (year < 1900 || year > 2100) {
            warnings.add("implausible year: " + year);
        }
        if (venue == null || venue.isBlank()) {
            warnings.add("missing venue");
        }
        if (doi == null || doi.isBlank()) {
            warnings.add("missing DOI");
        }
        return warnings;
    }
}
