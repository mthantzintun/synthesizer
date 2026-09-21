package org.thesis.research.litreview.service.synthesis;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for the clustering maths behind the future-work miner.
 *
 * <p>Clustering is deterministic by design - the "N papers agree" number is
 * something the thesis will cite - so the behaviour at and around the threshold
 * is pinned here rather than left to be discovered against a live corpus.
 */
class FutureWorkMinerTest {

    private static final double THRESHOLD = 0.62;

    // ------------------------------------------------------------- cosine

    @Test
    void cosineOfIdenticalVectorsIsOne() {
        float[] a = { 1f, 2f, 3f };
        assertThat(FutureWorkMiner.cosine(a, a)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void cosineOfOppositeVectorsIsMinusOne() {
        assertThat(FutureWorkMiner.cosine(new float[] { 1f, 0f }, new float[] { -1f, 0f }))
                .isCloseTo(-1.0, within(1e-9));
    }

    @Test
    void cosineOfOrthogonalVectorsIsZero() {
        assertThat(FutureWorkMiner.cosine(new float[] { 1f, 0f }, new float[] { 0f, 1f }))
                .isCloseTo(0.0, within(1e-9));
    }

    @Test
    void cosineIsScaleInvariantSoVectorMagnitudeDoesNotMatter() {
        float[] small = { 1f, 1f };
        float[] large = { 100f, 100f };
        assertThat(FutureWorkMiner.cosine(small, large)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void cosineOfAZeroVectorIsZeroRatherThanNaN() {
        // A zero vector has no direction; returning NaN would poison the
        // comparison and silently drop the statement from every cluster.
        assertThat(FutureWorkMiner.cosine(new float[] { 0f, 0f }, new float[] { 1f, 1f }))
                .isEqualTo(0.0);
    }

    // ------------------------------------------------------------ clustering

    @Test
    void similarVectorsLandInOneCluster() {
        List<float[]> vectors = List.of(
                new float[] { 1f, 0.1f },
                new float[] { 1f, 0.2f },
                new float[] { 1f, 0.15f });

        List<List<Integer>> groups = FutureWorkMiner.cluster(vectors, THRESHOLD);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).containsExactly(0, 1, 2);
    }

    @Test
    void dissimilarVectorsLandInSeparateClusters() {
        List<float[]> vectors = List.of(
                new float[] { 1f, 0f },
                new float[] { 0f, 1f });

        List<List<Integer>> groups = FutureWorkMiner.cluster(vectors, THRESHOLD);

        assertThat(groups).hasSize(2);
    }

    @Test
    void aStatementJustBelowTheThresholdSeedsItsOwnCluster() {
        // angle between {1,0} and {1,1} is 45 degrees -> cosine ~0.707, above.
        // {1,2} -> cosine ~0.447, below.
        List<float[]> vectors = List.of(
                new float[] { 1f, 0f },
                new float[] { 1f, 2f });

        List<List<Integer>> groups = FutureWorkMiner.cluster(vectors, THRESHOLD);

        assertThat(groups).hasSize(2);
    }

    @Test
    void theFirstMemberSeedsTheClusterSoOrderDecidesTheReferencePoint() {
        // 0 and 1 are close, 2 is close to 1 but not to 0. Against a seed-based
        // rule 2 joins only if it matches the seed, which is what keeps a chain
        // of loosely-related statements from collapsing into one mega-cluster.
        List<float[]> vectors = List.of(
                new float[] { 1f, 0f },
                new float[] { 0.8f, 0.6f },
                new float[] { 0f, 1f });

        List<List<Integer>> groups = FutureWorkMiner.cluster(vectors, THRESHOLD);

        // 1 matches seed 0 (cosine 0.8); 2 does not match seed 0 (cosine 0)
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0)).containsExactly(0, 1);
        assertThat(groups.get(1)).containsExactly(2);
    }

    @Test
    void everyInputIndexAppearsExactlyOnce() {
        List<float[]> vectors = List.of(
                new float[] { 1f, 0f },
                new float[] { 0.9f, 0.1f },
                new float[] { 0f, 1f },
                new float[] { 0.1f, 0.9f },
                new float[] { 1f, 1f });

        List<List<Integer>> groups = FutureWorkMiner.cluster(vectors, THRESHOLD);

        List<Integer> flattened = groups.stream().flatMap(List::stream).sorted().toList();
        assertThat(flattened).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void clusteringIsDeterministicForTheSameInput() {
        List<float[]> vectors = List.of(
                new float[] { 1f, 0f },
                new float[] { 0.9f, 0.1f },
                new float[] { 0f, 1f },
                new float[] { 0.1f, 0.9f });

        assertThat(FutureWorkMiner.cluster(vectors, THRESHOLD))
                .isEqualTo(FutureWorkMiner.cluster(vectors, THRESHOLD));
    }

    @Test
    void emptyInputProducesNoClusters() {
        assertThat(FutureWorkMiner.cluster(List.of(), THRESHOLD)).isEmpty();
    }
}
