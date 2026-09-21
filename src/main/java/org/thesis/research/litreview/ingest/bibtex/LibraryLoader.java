package org.thesis.research.litreview.ingest.bibtex;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jbibtex.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.thesis.research.litreview.config.LitreviewProperties;

/**
 * Phase 1 entry point: read {@code library.bib}, rewrite the reference-manager
 * attachment paths to real project paths, validate, and flag duplicates.
 */
@Service
public class LibraryLoader {

    private static final Logger log = LoggerFactory.getLogger(LibraryLoader.class);

    private final BibtexParser parser;
    private final LitreviewProperties props;
    private final ResourceLoader resourceLoader;

    public LibraryLoader(BibtexParser parser, LitreviewProperties props) {
        this(parser, props, new DefaultResourceLoader());
    }

    LibraryLoader(BibtexParser parser, LitreviewProperties props, ResourceLoader resourceLoader) {
        this.parser = parser;
        this.props = props;
        this.resourceLoader = resourceLoader;
    }

    /** Loads the library configured under {@code litreview.bib-file}. */
    public LibraryReport load() {
        return load(props.bibFile(), Path.of(props.pdfRoot()));
    }

    public LibraryReport load(String bibLocation, Path pdfRoot) {
        List<PaperMeta> parsed = readEntries(bibLocation);

        PdfPathResolver resolver = new PdfPathResolver(pdfRoot);
        List<PaperMeta> resolved = resolver.resolveAll(parsed);

        List<PaperMeta> valid = new ArrayList<>();
        Map<String, List<String>> invalid = new LinkedHashMap<>();
        Map<String, List<String>> warnings = new LinkedHashMap<>();
        Map<String, String> duplicates = new LinkedHashMap<>();

        Map<String, String> seenDoi = new HashMap<>();
        Map<String, String> seenTitle = new HashMap<>();

        for (PaperMeta paper : resolved) {
            List<String> problems = new ArrayList<>(paper.validate());

            if (paper.hasPdf() && !Files.isReadable(paper.pdfPath())) {
                problems.add("PDF is not readable: " + paper.pdfPath());
            }

            // Duplicate detection: a shared DOI is conclusive, an identical
            // normalized title is strong enough to warrant a warning. The
            // library has a known case (RN60/RN67 are the same review).
            // Ingesting both would double-count the same evidence in every
            // downstream count, so the second copy is rejected.
            String dupeOf = null;
            if (paper.doi() != null) {
                String key = paper.doi().toLowerCase(Locale.ROOT);
                dupeOf = seenDoi.putIfAbsent(key, paper.citekey());
            }
            if (dupeOf == null && paper.title() != null) {
                String key = PdfPathResolver.normalize(paper.title());
                dupeOf = seenTitle.putIfAbsent(key, paper.citekey());
            }
            if (dupeOf != null) {
                duplicates.put(paper.citekey(), dupeOf);
                problems.add("duplicate of " + dupeOf);
            }

            if (problems.isEmpty()) {
                valid.add(paper);
                List<String> warns = paper.warnings();
                if (!warns.isEmpty()) {
                    warnings.put(paper.citekey(), warns);
                }
            }
            else {
                invalid.put(paper.citekey(), problems);
            }
        }

        LibraryReport report = new LibraryReport(
                List.copyOf(valid), Map.copyOf(invalid), Map.copyOf(warnings),
                Map.copyOf(duplicates), resolver.unclaimed(resolved));

        log.info(report.summary());
        return report;
    }

    private List<PaperMeta> readEntries(String bibLocation) {
        try (Reader reader = openReader(bibLocation)) {
            return parser.parse(reader);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Could not read BibTeX file: " + bibLocation, e);
        }
        catch (ParseException e) {
            throw new IllegalStateException("Malformed BibTeX file: " + bibLocation, e);
        }
    }

    /** Accepts {@code classpath:}, {@code file:} and bare filesystem paths. */
    private Reader openReader(String location) throws IOException {
        if (location.startsWith("classpath:") || location.startsWith("file:")) {
            Resource resource = resourceLoader.getResource(location);
            if (!resource.exists()) {
                throw new IOException("BibTeX resource does not exist: " + location);
            }
            return new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8);
        }
        Path path = Path.of(location);
        if (!Files.isReadable(path)) {
            throw new IOException("BibTeX file is not readable: " + path.toAbsolutePath());
        }
        return Files.newBufferedReader(path, StandardCharsets.UTF_8);
    }
}
