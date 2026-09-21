package org.thesis.research.litreview.dto;

import java.util.List;

/**
 * One cell of the gap grid: a construct, a canonical section, and who covers it.
 *
 * <p>The grid's job is to make absence visible. A construct that appears in
 * twelve papers' introductions but in no paper's results section is a different
 * kind of gap from one that nobody studies at all, and the two need to be
 * distinguishable at a glance - hence counting per section rather than a single
 * total.
 *
 * @param construct        the construct
 * @param canonicalSection the IMRaD slot the coverage is counted within
 * @param coverageCount    how many papers address this construct in that slot
 * @param citekeys         which papers those are
 */
public record CoverageCell(
        String construct,
        String canonicalSection,
        int coverageCount,
        List<String> citekeys) {

    public static CoverageCell of(String construct, String canonicalSection, List<String> citekeys) {
        return new CoverageCell(construct, canonicalSection, citekeys.size(), List.copyOf(citekeys));
    }
}
