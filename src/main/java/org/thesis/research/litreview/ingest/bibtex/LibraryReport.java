package org.thesis.research.litreview.ingest.bibtex;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Outcome of loading and validating the BibTeX library.
 *
 * @param valid     entries that are ingestable (citable + PDF resolved)
 * @param invalid   entries rejected outright, mapped to the blocking reasons
 * @param warnings  accepted entries that have non-blocking metadata gaps
 * @param duplicates entries sharing a DOI or normalized title with an earlier entry
 * @param unclaimedPdfs PDFs on disk that no bib entry pointed at
 */
public record LibraryReport(
        List<PaperMeta> valid,
        Map<String, List<String>> invalid,
        Map<String, List<String>> warnings,
        Map<String, String> duplicates,
        List<Path> unclaimedPdfs) {

    public int total() {
        return valid.size() + invalid.size();
    }

    /** Papers that carry a usable year - the subset the gap grid can chart. */
    public List<PaperMeta> withYear() {
        return valid.stream().filter(p -> p.year() != null).toList();
    }

    /** Multi-line human-readable summary, logged at the end of a load. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("BibTeX library: %d ingestable / %d total".formatted(valid.size(), total()));

        if (!invalid.isEmpty()) {
            sb.append("\n  rejected (").append(invalid.size()).append("):");
            invalid.forEach((citekey, problems) ->
                    sb.append("\n    - ").append(citekey).append(": ").append(String.join(", ", problems)));
        }
        if (!warnings.isEmpty()) {
            sb.append("\n  accepted with warnings (").append(warnings.size()).append("):");
            warnings.forEach((citekey, warns) ->
                    sb.append("\n    ~ ").append(citekey).append(": ").append(String.join(", ", warns)));
        }
        if (!duplicates.isEmpty()) {
            sb.append("\n  duplicates (").append(duplicates.size()).append("):");
            duplicates.forEach((citekey, of) ->
                    sb.append("\n    - ").append(citekey).append(" duplicates ").append(of));
        }
        if (!unclaimedPdfs.isEmpty()) {
            sb.append("\n  PDFs on disk with no bib entry (").append(unclaimedPdfs.size()).append("):");
            unclaimedPdfs.forEach(p -> sb.append("\n    - ").append(p.getFileName()));
        }
        return sb.toString();
    }
}
