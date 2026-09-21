package org.thesis.research.litreview.service.synthesis;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.thesis.research.litreview.dto.CoverageCell;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the gap grid's pure logic.
 *
 * <p>The grid is the one synthesis output that is entirely deterministic, and
 * its value is in the empty cells - so the tests are mostly about whether
 * absence is represented correctly rather than about counting.
 */
class GapGridServiceTest {

    @Test
    void coverageCellReportsTheCountFromItsCitekeys() {
        CoverageCell cell = CoverageCell.of("visibility", "RESULTS", List.of("RN1", "RN2", "RN3"));

        assertThat(cell.construct()).isEqualTo("visibility");
        assertThat(cell.canonicalSection()).isEqualTo("RESULTS");
        assertThat(cell.coverageCount()).isEqualTo(3);
        assertThat(cell.citekeys()).containsExactly("RN1", "RN2", "RN3");
    }

    @Test
    void anEmptyCellHasZeroCoverageAndNoCitekeys() {
        // The empty cell is the finding. It must be representable, not omitted.
        CoverageCell cell = CoverageCell.of("blockchain traceability", "RESULTS", List.of());

        assertThat(cell.coverageCount()).isZero();
        assertThat(cell.citekeys()).isEmpty();
    }

    @Test
    void citekeysAreDefensivelyCopiedSoACellCannotBeMutated() {
        List<String> mutable = new java.util.ArrayList<>(List.of("RN1"));
        CoverageCell cell = CoverageCell.of("visibility", "METHOD", mutable);

        mutable.add("RN2");

        assertThat(cell.citekeys()).containsExactly("RN1");
        assertThat(cell.coverageCount()).isEqualTo(1);
    }
}
