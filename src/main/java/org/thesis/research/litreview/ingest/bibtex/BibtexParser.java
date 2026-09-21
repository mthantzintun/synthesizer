package org.thesis.research.litreview.ingest.bibtex;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jbibtex.BibTeXDatabase;
import org.jbibtex.BibTeXEntry;
import org.jbibtex.BibTeXParser;
import org.jbibtex.Key;
import org.jbibtex.LaTeXObject;
import org.jbibtex.LaTeXParser;
import org.jbibtex.LaTeXPrinter;
import org.jbibtex.ParseException;
import org.jbibtex.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads a BibTeX library into {@link PaperMeta} records.
 *
 * <p>Normalization performed here:
 * <ul>
 *   <li>LaTeX escapes / accents decoded to plain Unicode</li>
 *   <li>{@code author} converted from {@code "A and B and C"} to {@code "A; B; C"}</li>
 *   <li>{@code venue} coalesced from journal &rarr; booktitle &rarr; publisher</li>
 *   <li>{@code DOI} stripped of any resolver prefix</li>
 *   <li>{@code year} pulled out of noisy values such as {@code "c2008"}</li>
 * </ul>
 *
 * <p>PDF path resolution is deliberately <em>not</em> done here - see
 * {@link PdfPathResolver}. This class stays pure and trivially unit-testable.
 */
@Component
public class BibtexParser {

    private static final Logger log = LoggerFactory.getLogger(BibtexParser.class);

    /** EndNote writes {@code file = {internal-pdf://1234567/Some Truncated Name.pdf}}. */
    private static final Pattern INTERNAL_PDF = Pattern.compile("^internal-pdf://\\d*/?", Pattern.CASE_INSENSITIVE);

    private static final Pattern YEAR = Pattern.compile("(1[89]\\d{2}|20\\d{2}|21\\d{2})");

    private static final Pattern DOI_PREFIX =
            Pattern.compile("^(https?://)?(dx\\.)?doi\\.org/", Pattern.CASE_INSENSITIVE);

    private static final Key KEY_ABSTRACT = new Key("abstract");
    private static final Key KEY_FILE = new Key("file");

    private final LaTeXParser latexParser;
    private final LaTeXPrinter latexPrinter = new LaTeXPrinter();

    public BibtexParser() {
        try {
            this.latexParser = new LaTeXParser();
        }
        catch (ParseException e) {
            throw new IllegalStateException("Could not initialise the LaTeX parser", e);
        }
    }

    public List<PaperMeta> parse(Path bibFile) throws IOException, ParseException {
        try (Reader reader = Files.newBufferedReader(bibFile, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    public List<PaperMeta> parse(Reader reader) throws IOException, ParseException {
        // Tolerant parser: EndNote exports routinely contain unresolved @string
        // macros and non-standard fields. One bad entry must not kill the batch.
        BibTeXParser parser = new BibTeXParser() {
            @Override
            public void checkStringResolution(Key key, org.jbibtex.BibTeXString string) {
                if (string == null) {
                    log.debug("Unresolved BibTeX string macro '{}' - keeping the literal value", key);
                    return;
                }
                super.checkStringResolution(key, string);
            }
        };

        BibTeXDatabase db = parser.parse(reader);

        List<PaperMeta> papers = db.getEntries().values().stream()
                .map(this::toPaperMeta)
                .filter(java.util.Objects::nonNull)
                .toList();

        log.info("Parsed {} BibTeX entries", papers.size());
        return papers;
    }

    private PaperMeta toPaperMeta(BibTeXEntry e) {
        try {
            String citekey = e.getKey() == null ? null : e.getKey().getValue();
            String entryType = e.getType() == null ? "misc" : e.getType().getValue().toLowerCase();

            String venue = firstNonBlank(
                    field(e, BibTeXEntry.KEY_JOURNAL),
                    field(e, BibTeXEntry.KEY_BOOKTITLE),
                    field(e, BibTeXEntry.KEY_PUBLISHER),
                    field(e, BibTeXEntry.KEY_INSTITUTION),
                    field(e, BibTeXEntry.KEY_SCHOOL));

            return new PaperMeta(
                    citekey,
                    entryType,
                    field(e, BibTeXEntry.KEY_TITLE),
                    normalizeAuthors(field(e, BibTeXEntry.KEY_AUTHOR)),
                    parseYear(field(e, BibTeXEntry.KEY_YEAR)),
                    venue,
                    normalizeDoi(field(e, BibTeXEntry.KEY_DOI)),
                    field(e, KEY_ABSTRACT),
                    declaredFilename(field(e, KEY_FILE)),
                    null);
        }
        catch (RuntimeException ex) {
            log.warn("Skipping malformed BibTeX entry '{}': {}", e.getKey(), ex.getMessage());
            return null;
        }
    }

    /**
     * Pulls the bare filename out of a {@code file} field. Handles EndNote's
     * {@code internal-pdf://} scheme, Zotero's {@code :path:mimetype} triples,
     * multiple semicolon-separated attachments, and Windows/Unix separators.
     *
     * @return the filename, or null when no PDF attachment is declared
     */
    String declaredFilename(String rawFileField) {
        if (rawFileField == null || rawFileField.isBlank()) {
            return null;
        }
        // Zotero: "Smith 2020 - Title:files/12/Smith.pdf:application/pdf"
        // EndNote: "internal-pdf://0572211779/City logistics Challenges.pdf"
        return Arrays.stream(rawFileField.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(candidate -> {
                    String value = candidate;
                    if (value.contains(":") && value.toLowerCase().contains("application/pdf")) {
                        String[] parts = value.split(":");
                        value = parts.length >= 2 ? parts[parts.length - 2] : value;
                    }
                    Matcher m = INTERNAL_PDF.matcher(value);
                    if (m.find()) {
                        value = m.replaceFirst("");
                    }
                    // keep only the last path segment
                    int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
                    return slash >= 0 ? value.substring(slash + 1) : value;
                })
                .map(String::trim)
                .filter(s -> s.toLowerCase().endsWith(".pdf"))
                .findFirst()
                .orElse(null);
    }

    /** {@code "Smith, J. and Jones, K."} &rarr; {@code "Smith, J.; Jones, K."}, de-duplicated. */
    String normalizeAuthors(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // the parser already decoded braces; split on the BibTeX "and" separator
        Set<String> names = Arrays.stream(raw.split("(?i)\\s+and\\s+"))
                .map(s -> s.replaceAll("\\s+", " ").trim())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return names.isEmpty() ? null : String.join("; ", names);
    }

    /** Tolerates {@code "c2008"}, {@code "2022-05"}, {@code "in press"}. */
    Integer parseYear(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = YEAR.matcher(raw);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    String normalizeDoi(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String doi = DOI_PREFIX.matcher(raw.trim()).replaceFirst("");
        return doi.isBlank() ? null : doi;
    }

    /** Reads a field and decodes any LaTeX markup it contains. */
    private String field(BibTeXEntry entry, Key key) {
        Value value = entry.getField(key);
        if (value == null) {
            return null;
        }
        String raw = value.toUserString();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return decodeLatex(raw);
    }

    private String decodeLatex(String raw) {
        String out = raw;
        try {
            List<LaTeXObject> objects = latexParser.parse(raw);
            out = latexPrinter.print(objects);
        }
        catch (ParseException | org.jbibtex.TokenMgrException ex) {
            // Unbalanced math mode or a stray backslash - fall back to the raw value.
            log.trace("LaTeX decode failed, using raw value: {}", ex.getMessage());
        }
        // collapse the whitespace LaTeX decoding tends to introduce and drop
        // the grouping braces EndNote sprinkles over proper nouns
        return out.replace("{", "").replace("}", "").replaceAll("\\s+", " ").trim();
    }

    private static String firstNonBlank(String... values) {
        return Optional.ofNullable(values)
                .flatMap(vs -> Arrays.stream(vs).filter(v -> v != null && !v.isBlank()).findFirst())
                .orElse(null);
    }
}
