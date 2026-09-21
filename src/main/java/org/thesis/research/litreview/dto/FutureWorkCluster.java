package org.thesis.research.litreview.dto;

import java.util.List;

import org.thesis.research.litreview.entity.FutureWorkClusterEntity;

/**
 * A theme that two or more papers independently raised as future work.
 *
 * <p>Consensus strength ({@code paperCount}) is the interesting number here: a
 * direction that five papers name and nobody has pursued is a far better gap
 * candidate than one a single paper mentions.
 *
 * @param id         {@code future_work_cluster.id}
 * @param theme      the cluster's label
 * @param paperCount how many papers raised it
 * @param citekeys   the papers that raised it, for verification
 */
public record FutureWorkCluster(
        Long id,
        String theme,
        int paperCount,
        List<String> citekeys) {

    public static FutureWorkCluster from(FutureWorkClusterEntity entity, List<String> citekeys) {
        return new FutureWorkCluster(entity.getId(), entity.getTheme(), entity.getPaperCount(),
                List.copyOf(citekeys));
    }
}
