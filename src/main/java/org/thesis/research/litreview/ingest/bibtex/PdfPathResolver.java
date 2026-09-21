package org.thesis.research.litreview.ingest.bibtex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rewrites the reference-manager attachment path in each {@link PaperMeta} to a
 * real path inside the project's PDF store.
 *
 * <p>This is harder than a map lookup because EndNote <em>truncates</em> the
 * filename it records to ~46 characters, and the copy on disk often carries an
 * extra suffix:
 *
 * <pre>
 *   bib  : internal-pdf://0572211779/City logistics Challenges and opportunities fo.pdf
 *   disk : City logistics Challenges and opportunities for technology providers - Sep 19, 2026.pdf
 * </pre>
 *
 * <p>Resolution therefore runs a cascade, stopping at the first hit:
 * <ol>
 *   <li><b>exact</b> - normalized filenames are equal</li>
 *   <li><b>prefix</b> - the (truncated) bib name is a prefix of the disk name</li>
 *   <li><b>title prefix</b> - the entry's title is a prefix of the disk name</li>
 *   <li><b>fuzzy</b> - highest Jaro-Winkler score above {@value #FUZZY_THRESHOLD}</li>
 * </ol>
 *
 * <p>Each PDF is claimed by at most one citekey, so two near-identical entries
 * (e.g. the duplicated {@code RN60}/{@code RN67} pair) cannot both grab the same
 * file - the second one is reported as unresolved instead of silently
 * double-ingesting the same text.
 */
public class PdfPathResolver {

    private static final Logger log = LoggerFactory.getLogger(PdfPathResolver.class);

    private static final double FUZZY_THRESHOLD = 0.88;

    /** filename (normalized) -> path, for every PDF under the root */
    private final Map<String, Path> byNormalizedName = new HashMap<>();

    private final List<Path> allPdfs = new ArrayList<>();

    private final Path root;

    public PdfPathResolver(Path pdfRoot) {
        this.root = pdfRoot.toAbsolutePath().normalize();
        index();
    }

    private void index() {
        if (!Files.isDirectory(root)) {
            log.warn("PDF root '{}' does not exist - no attachment will resolve", root);
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted()
                    .forEach(p -> {
                        allPdfs.add(p);
                        byNormalizedName.putIfAbsent(normalize(stripExtension(p.getFileName().toString())), p);
                    });
        }
        catch (IOException e) {
            throw new UncheckedIOException("Could not scan PDF root " + root, e);
        }
        log.info("Indexed {} PDFs under {}", allPdfs.size(), root);
    }

    /**
     * Resolves every entry, returning new records with {@code pdfPath} populated
     * where a match was found. Input order is preserved.
     */
    public List<PaperMeta> resolveAll(List<PaperMeta> papers) {
        Set<Path> claimed = new HashSet<>();
        List<PaperMeta> out = new ArrayList<>(papers.size());

        // Deterministic claiming: entries with a declared filename get first
        // refusal, so a title-only guess can never steal a confident match.
        List<PaperMeta> ordered = new ArrayList<>(papers);
        ordered.sort(Comparator.comparing((PaperMeta p) -> p.declaredFile() == null)
                .thenComparing(p -> p.citekey() == null ? "" : p.citekey()));

        Map<String, Path> resolved = new HashMap<>();
        for (PaperMeta paper : ordered) {
            resolve(paper, claimed).ifPresent(path -> {
                claimed.add(path);
                resolved.put(paper.citekey(), path);
            });
        }

        for (PaperMeta paper : papers) {
            out.add(paper.withPdfPath(resolved.get(paper.citekey())));
        }
        return out;
    }

    private java.util.Optional<Path> resolve(PaperMeta paper, Set<Path> claimed) {
        String declared = paper.declaredFile() == null ? null : normalize(stripExtension(paper.declaredFile()));

        // 1. exact normalized filename
        if (declared != null) {
            Path exact = byNormalizedName.get(declared);
            if (exact != null && !claimed.contains(exact)) {
                log.debug("{}: exact match -> {}", paper.citekey(), exact.getFileName());
                return java.util.Optional.of(exact);
            }
        }

        // 2. truncated bib name is a prefix of the on-disk name
        if (declared != null && declared.length() >= 12) {
            java.util.Optional<Path> prefix = candidates(claimed)
                    .filter(p -> normalize(stripExtension(p.getFileName().toString())).startsWith(declared))
                    // shortest wins: the least amount of extra suffix is the best fit
                    .min(Comparator.comparingInt(p -> p.getFileName().toString().length()));
            if (prefix.isPresent()) {
                log.debug("{}: prefix match -> {}", paper.citekey(), prefix.get().getFileName());
                return prefix;
            }
        }

        // 3. the title is a prefix of the on-disk name
        String title = paper.title() == null ? null : normalize(paper.title());
        if (title != null && title.length() >= 12) {
            java.util.Optional<Path> byTitle = candidates(claimed)
                    .filter(p -> {
                        String disk = normalize(stripExtension(p.getFileName().toString()));
                        return disk.startsWith(title) || title.startsWith(disk);
                    })
                    .min(Comparator.comparingInt(p -> p.getFileName().toString().length()));
            if (byTitle.isPresent()) {
                log.debug("{}: title match -> {}", paper.citekey(), byTitle.get().getFileName());
                return byTitle;
            }
        }

        // 4. fuzzy - compare against whichever probe string we have
        String probe = declared != null ? declared : title;
        if (probe != null) {
            Path best = null;
            double bestScore = 0;
            for (Path p : candidates(claimed).toList()) {
                String disk = normalize(stripExtension(p.getFileName().toString()));
                // truncate the disk name to the probe length so a long suffix
                // ("- Sep 19, 2026") does not dilute the score
                String clipped = disk.length() > probe.length() ? disk.substring(0, probe.length()) : disk;
                double score = jaroWinkler(probe, clipped);
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
            if (best != null && bestScore >= FUZZY_THRESHOLD) {
                log.debug("{}: fuzzy match ({}) -> {}", paper.citekey(),
                        String.format("%.3f", bestScore), best.getFileName());
                return java.util.Optional.of(best);
            }
        }

        log.warn("{}: could not resolve attachment '{}' (title: '{}')",
                paper.citekey(), paper.declaredFile(), paper.title());
        return java.util.Optional.empty();
    }

    private Stream<Path> candidates(Set<Path> claimed) {
        return allPdfs.stream().filter(p -> !claimed.contains(p));
    }

    /** PDFs present on disk that no bib entry claimed - useful for spotting gaps in the library. */
    public List<Path> unclaimed(List<PaperMeta> resolvedPapers) {
        Set<Path> used = resolvedPapers.stream()
                .map(PaperMeta::pdfPath)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        return allPdfs.stream().filter(p -> !used.contains(p)).toList();
    }

    public int indexedCount() {
        return allPdfs.size();
    }

    // ---------------------------------------------------------------- helpers

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /**
     * Folds case, strips diacritics and collapses everything that is not a
     * letter or digit. Makes {@code "Last-Mile"}, {@code "last mile"} and
     * {@code "LastMile"} compare equal, which matters because reference
     * managers rewrite punctuation freely.
     */
    static String normalize(String s) {
        String decomposed = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Jaro-Winkler similarity in [0,1]; favours strings that agree at the start. */
    static double jaroWinkler(String a, String b) {
        double jaro = jaro(a, b);
        int prefix = 0;
        int limit = Math.min(4, Math.min(a.length(), b.length()));
        while (prefix < limit && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        return jaro + (0.1 * prefix * (1.0 - jaro));
    }

    private static double jaro(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return a.equals(b) ? 1.0 : 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        int window = Math.max(a.length(), b.length()) / 2 - 1;
        boolean[] aMatched = new boolean[a.length()];
        boolean[] bMatched = new boolean[b.length()];

        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            int start = Math.max(0, i - window);
            int end = Math.min(i + window + 1, b.length());
            for (int j = start; j < end; j++) {
                if (bMatched[j] || a.charAt(i) != b.charAt(j)) {
                    continue;
                }
                aMatched[i] = true;
                bMatched[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) {
            return 0.0;
        }

        double transpositions = 0;
        int k = 0;
        for (int i = 0; i < a.length(); i++) {
            if (!aMatched[i]) {
                continue;
            }
            while (!bMatched[k]) {
                k++;
            }
            if (a.charAt(i) != b.charAt(k)) {
                transpositions++;
            }
            k++;
        }
        transpositions /= 2;

        return ((double) matches / a.length()
                + (double) matches / b.length()
                + (matches - transpositions) / matches) / 3.0;
    }
}

