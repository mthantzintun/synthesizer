package org.thesis.research.litreview.entity;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the ranking maths in {@link ChunkJdbcRepository}.
 *
 * <p>The fused query is the subtlest piece of logic in the pipeline and cannot
 * be exercised without Postgres, so the formula is duplicated here as a pure
 * function and pinned. If the SQL and this test disagree, one of them is wrong.
 */
class ChunkJdbcRepositoryTest {

    private static final int RRF_K = 60;

    @Test
    void agreesWithTheReciprocalRankFormula() {
        // rank 1 in the vector arm only: 1/(60+1)
        Map<Long, Double> fused = ChunkJdbcRepository.fuse(List.of(10L), List.of(), RRF_K);
        assertThat(fused.get(10L)).isEqualTo(1.0 / 61);

        // rank 2 in the text arm only: 1/(60+2)
        fused = ChunkJdbcRepository.fuse(List.of(), List.of(7L, 20L), RRF_K);
        assertThat(fused.get(7L)).isEqualTo(1.0 / 61);
        assertThat(fused.get(20L)).isEqualTo(1.0 / 62);
    }

    @Test
    void sumsTheContributionsWhenAChunkAppearsInBothArms() {
        // 10 is rank 1 in both arms, 20 is rank 2 in both
        Map<Long, Double> fused = ChunkJdbcRepository.fuse(
                List.of(10L, 20L), List.of(10L, 20L), RRF_K);

        assertThat(fused.get(10L)).isEqualTo(2.0 / 61);
        assertThat(fused.get(20L)).isEqualTo(2.0 / 62);
    }

    @Test
    void aChunkRankedHighInBothArmsBeatsOneRankedHighInOnlyOne() {
        // 5 is first in both arms; 9 is first in vector but absent from text
        Map<Long, Double> fused = ChunkJdbcRepository.fuse(
                List.of(5L, 9L), List.of(5L), RRF_K);

        assertThat(ChunkJdbcRepository.rankByScore(fused)).containsExactly(5L, 9L);
    }

    @Test
    void agreementAcrossArmsOutranksASingleVeryHighRank() {
        // 100 appears at rank 1 and rank 2; 200 appears at rank 1 only.
        // Two decent ranks must beat one perfect rank - that is the whole point
        // of fusing rather than trusting a single arm.
        Map<Long, Double> fused = ChunkJdbcRepository.fuse(
                List.of(200L, 100L), List.of(100L), RRF_K);

        assertThat(ChunkJdbcRepository.rankByScore(fused)).containsExactly(100L, 200L);
    }

    @Test
    void ranksAreOrderedBestFirst() {
        Map<Long, Double> fused = ChunkJdbcRepository.fuse(
                List.of(1L, 2L, 3L), List.of(3L, 2L), RRF_K);

        // 1: 1/61 ; 2: 1/62 + 1/62 ; 3: 1/63 + 1/61
        assertThat(ChunkJdbcRepository.rankByScore(fused)).containsExactly(3L, 2L, 1L);
    }

    @Test
    void tiesBreakByChunkIdSoResultsAreStable() {
        // 10 is rank 1 in the vector arm and rank 2 in the text arm;
        // 20 is the mirror image. Same total, so the tie-break decides.
        Map<Long, Double> fused = ChunkJdbcRepository.fuse(
                List.of(10L, 20L), List.of(20L, 10L), RRF_K);

        assertThat(fused.get(10L)).isEqualTo(fused.get(20L));
        // lower id first, deterministically, rather than by hash order
        assertThat(ChunkJdbcRepository.rankByScore(fused)).containsExactly(10L, 20L);
    }

    @Test
    void noHitsFusesToNothing() {
        assertThat(ChunkJdbcRepository.fuse(List.of(), List.of(), RRF_K)).isEmpty();
        assertThat(ChunkJdbcRepository.rankByScore(Map.of())).isEmpty();
    }

    @Test
    void escapesLikeMetacharactersSoAConstructCannotBecomeAWildcard() {
        // A construct containing % or _ must match literally; unescaped, "100%"
        // would match every row and inflate the coverage count.
        assertThat(ChunkJdbcRepository.escapeLike("100% visibility")).isEqualTo("100\\% visibility");
        assertThat(ChunkJdbcRepository.escapeLike("last_mile")).isEqualTo("last\\_mile");
        assertThat(ChunkJdbcRepository.escapeLike("back\\slash")).isEqualTo("back\\\\slash");
        assertThat(ChunkJdbcRepository.escapeLike("plain text")).isEqualTo("plain text");
    }

    // ------------------------------------------------- vector text literals

    @Test
    void rendersAVectorInPgvectorTextForm() {
        // This string is bound as a parameter and cast by ?::vector, so the
        // format must be exactly what pgvector parses.
        assertThat(ChunkJdbcRepository.toVectorLiteral(new float[] { 1f, 2.5f, -0.25f }))
                .isEqualTo("[1.0,2.5,-0.25]");
    }

    @Test
    void rendersAThreeEightyFourDimensionalVectorWithAllComponents() {
        // The real width. A dropped or duplicated component would produce a
        // vector of the wrong dimension and fail at the database instead.
        float[] vector = new float[384];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = i / 384f;
        }

        String literal = ChunkJdbcRepository.toVectorLiteral(vector);

        assertThat(literal).startsWith("[").endsWith("]");
        assertThat(literal.split(",", -1)).hasSize(384);
    }

    @Test
    void doesNotLosePrecision() {
        // Float.toString is used rather than %f precisely so components are not
        // truncated - a truncated component silently shifts every cosine
        // distance and therefore every search result order.
        assertThat(ChunkJdbcRepository.toVectorLiteral(new float[] { 0.123456789f }))
                .isEqualTo("[" + Float.toString(0.123456789f) + "]");
    }

    @Test
    void handlesScientificNotationWithoutProducingInvalidSyntax() {
        // Very small embedding components render as "1.0E-7" in Java. pgvector
        // accepts that, but the comma separators must still be right.
        assertThat(ChunkJdbcRepository.toVectorLiteral(new float[] { 1.0E-7f, 2f }))
                .isEqualTo("[1.0E-7,2.0]");
    }

    @Test
    void rejectsAnEmptyVectorRatherThanWritingAnInvalidLiteral() {
        // "[]" would fail at the database with an opaque cast error; failing
        // here names the actual problem.
        assertThatThrownBy(() -> ChunkJdbcRepository.toVectorLiteral(new float[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
        assertThatThrownBy(() -> ChunkJdbcRepository.toVectorLiteral(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
