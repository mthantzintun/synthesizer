package org.thesis.research.litreview.venue.grobid;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the heading &rarr; canonical-slot mapping.
 *
 * <p>The gap grid's entire claim rests on this mapping: "no paper tests this
 * construct" is only true if every heading that means "Results" was actually
 * classified as RESULTS. Headings in this corpus are inconsistent
 * ("4. Results and Discussion", "Findings", "Empirical analysis"), so the
 * interesting cases are the awkward ones.
 */
class TeiDocumentCanonicalSectionTest {

    @Test
    void stripsLeadingNumberingBeforeMatching() {
        assertThat(TeiDocument.CanonicalSection.of("3.1. Method"))
                .isEqualTo(TeiDocument.CanonicalSection.METHOD);
        assertThat(TeiDocument.CanonicalSection.of("IV. Results"))
                .isEqualTo(TeiDocument.CanonicalSection.RESULTS);
        assertThat(TeiDocument.CanonicalSection.of("2) Discussion"))
                .isEqualTo(TeiDocument.CanonicalSection.DISCUSSION);
    }

    @Test
    void recognizesTheImradSections() {
        assertThat(TeiDocument.CanonicalSection.of("Introduction"))
                .isEqualTo(TeiDocument.CanonicalSection.INTRODUCTION);
        assertThat(TeiDocument.CanonicalSection.of("Methodology"))
                .isEqualTo(TeiDocument.CanonicalSection.METHOD);
        assertThat(TeiDocument.CanonicalSection.of("Findings"))
                .isEqualTo(TeiDocument.CanonicalSection.RESULTS);
        assertThat(TeiDocument.CanonicalSection.of("Discussion"))
                .isEqualTo(TeiDocument.CanonicalSection.DISCUSSION);
        assertThat(TeiDocument.CanonicalSection.of("Conclusion"))
                .isEqualTo(TeiDocument.CanonicalSection.CONCLUSION);
    }

    @Test
    void futureWorkWinsOverTheBroaderDiscussionMatch() {
        // "Limitations and future research" contains neither "limitation" only -
        // it must not be swallowed by the LIMITATIONS or DISCUSSION branch, or
        // the future-work miner would miss the statement entirely.
        assertThat(TeiDocument.CanonicalSection.of("Limitations and future research"))
                .isEqualTo(TeiDocument.CanonicalSection.FUTURE_WORK);
        assertThat(TeiDocument.CanonicalSection.of("Future work"))
                .isEqualTo(TeiDocument.CanonicalSection.FUTURE_WORK);
        assertThat(TeiDocument.CanonicalSection.of("Directions for future research"))
                .isEqualTo(TeiDocument.CanonicalSection.FUTURE_WORK);
    }

    @Test
    void limitationsIsRecognizedOnItsOwn() {
        assertThat(TeiDocument.CanonicalSection.of("Limitations of this study"))
                .isEqualTo(TeiDocument.CanonicalSection.LIMITATIONS);
    }

    @Test
    void resultsAndDiscussionIsClassifiedAsResults() {
        // Common in this corpus. RESULTS is checked before DISCUSSION, so the
        // empirical content lands in the section the gap grid treats as "tested".
        assertThat(TeiDocument.CanonicalSection.of("Results and Discussion"))
                .isEqualTo(TeiDocument.CanonicalSection.RESULTS);
    }

    @Test
    void recognizesLiteratureAndTheorySections() {
        assertThat(TeiDocument.CanonicalSection.of("Literature review"))
                .isEqualTo(TeiDocument.CanonicalSection.RELATED_WORK);
        assertThat(TeiDocument.CanonicalSection.of("Theoretical background"))
                .isEqualTo(TeiDocument.CanonicalSection.THEORY);
        assertThat(TeiDocument.CanonicalSection.of("Conceptual framework"))
                .isEqualTo(TeiDocument.CanonicalSection.THEORY);
    }

    @Test
    void unknownAndBlankHeadingsFallBackToOther() {
        assertThat(TeiDocument.CanonicalSection.of("Data and variables"))
                .isEqualTo(TeiDocument.CanonicalSection.OTHER);
        assertThat(TeiDocument.CanonicalSection.of(null))
                .isEqualTo(TeiDocument.CanonicalSection.OTHER);
        assertThat(TeiDocument.CanonicalSection.of("   "))
                .isEqualTo(TeiDocument.CanonicalSection.OTHER);
    }
}
