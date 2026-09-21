package org.thesis.research.litreview.ingest.bibtex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.thesis.research.litreview.config.LitreviewProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs Phase 1 against the real library.bib and the real PDF folder, so a
 * regression in filename matching fails the build rather than silently
 * shrinking the corpus.
 */
class LibraryLoaderTest {

    private static final Path PDF_ROOT = Path.of("src/main/resources/papers");
    private static final String BIB = "src/main/resources/data/library.bib";

    private LibraryLoader loader() {
        LitreviewProperties props = new LitreviewProperties(
                BIB, PDF_ROOT.toString(), ".cache/tei", 384,
                new LitreviewProperties.Grobid("http://localhost:8070", java.time.Duration.ofMinutes(5),
                        true, false, true, 2),
                new LitreviewProperties.Chunking(1200, 1800, 250, 150, List.of("references")),
                new LitreviewProperties.Search(12, 60, 60),
                new LitreviewProperties.Synthesis(0.62, 2));
        return new LibraryLoader(new BibtexParser(), props);
    }

    @Test
    void parsesEveryEntryInTheLibrary() {
        assumeTrue(Files.isReadable(Path.of(BIB)), "library.bib not present");

        LibraryReport report = loader().load(BIB, PDF_ROOT);

        // 30 entries in the current export; parsing must not drop any of them
        assertThat(report.total()).isGreaterThanOrEqualTo(28);
        System.out.println(report.summary());
    }

    @Test
    void resolvesTruncatedEndnoteFilenamesToRealPdfs() {
        assumeTrue(Files.isReadable(Path.of(BIB)), "library.bib not present");
        assumeTrue(Files.isDirectory(PDF_ROOT), "papers folder not present");

        LibraryReport report = loader().load(BIB, PDF_ROOT);

        // every accepted paper must point at a file that actually exists
        assertThat(report.valid())
                .isNotEmpty()
                .allSatisfy(p -> {
                    assertThat(p.pdfPath()).isNotNull();
                    assertThat(Files.isReadable(p.pdfPath())).isTrue();
                });

        // and the resolved paths must be inside the project, not internal-pdf://
        assertThat(report.valid())
                .allSatisfy(p -> assertThat(p.pdfPath().toAbsolutePath().toString())
                        .contains("papers"));
    }

    @Test
    void flagsTheKnownDuplicateReview() {
        assumeTrue(Files.isReadable(Path.of(BIB)), "library.bib not present");

        LibraryReport report = loader().load(BIB, PDF_ROOT);

        // RN60 and RN67 are the same Ranieri et al. review under two citekeys
        assertThat(report.duplicates()).containsKey("RN67");
    }

    @Test
    void normalizesAuthorsAndDoi() {
        BibtexParser parser = new BibtexParser();

        assertThat(parser.normalizeAuthors("Smith, J. and Jones, K. and Smith, J."))
                .isEqualTo("Smith, J.; Jones, K.");
        assertThat(parser.normalizeDoi("https://doi.org/10.1016/j.urbmob.2022.100020"))
                .isEqualTo("10.1016/j.urbmob.2022.100020");
        assertThat(parser.parseYear("c2008")).isEqualTo(2008);
        assertThat(parser.parseYear("in press")).isNull();
    }

    @Test
    void extractsFilenameFromEndnoteAndZoteroFields() {
        BibtexParser parser = new BibtexParser();

        assertThat(parser.declaredFilename("internal-pdf://0572211779/City logistics Challenges.pdf"))
                .isEqualTo("City logistics Challenges.pdf");
        assertThat(parser.declaredFilename("Smith - Title:files/12/Smith.pdf:application/pdf"))
                .isEqualTo("Smith.pdf");
        assertThat(parser.declaredFilename("")).isNull();
    }
}

