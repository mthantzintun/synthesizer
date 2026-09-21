package org.thesis.research.litreview.service.synthesis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thesis.research.litreview.config.LitreviewProperties;
import org.thesis.research.litreview.config.synthesis.EmbeddingConfig.EmbeddingBatcher;
import org.thesis.research.litreview.dto.FutureWorkCluster;
import org.thesis.research.litreview.entity.ExtractionEntity;
import org.thesis.research.litreview.entity.FutureWorkClusterEntity;
import org.thesis.research.litreview.entity.FutureWorkClusterRepository;
import org.thesis.research.litreview.service.extraction.ExtractionRepository;

/**
 * Clusters the "future work" statements across the corpus.
 *
 * <p>The signal being mined is consensus: a direction that several papers
 * independently name as open is much more likely to be a real gap than one a
 * single paper mentions. Two papers count as agreeing when their statements
 * embed close together - the threshold lives in
 * {@code litreview.synthesis.future-work-similarity-threshold}.
 *
 * <p>No LLM is used to form the clusters. Embedding similarity is cheap,
 * deterministic and reproducible, which matters because the cluster set is
 * something the thesis will cite; a generative pass here would make the
 * headline "these N papers agree" number move between runs. The LLM is only
 * invited in later, to phrase a theme, and only for clusters that survive.
 */
@Service
public class FutureWorkMiner {

    private static final Logger log = LoggerFactory.getLogger(FutureWorkMiner.class);

    private final ExtractionRepository extractions;
    private final FutureWorkClusterRepository clusters;
    private final EmbeddingBatcher embeddings;
    private final LitreviewProperties.Synthesis config;

    public FutureWorkMiner(ExtractionRepository extractions, FutureWorkClusterRepository clusters,
            EmbeddingBatcher embeddings, LitreviewProperties props) {
        this.extractions = extractions;
        this.clusters = clusters;
        this.embeddings = embeddings;
        this.config = props.synthesis();
    }

    /**
     * Rebuilds the future-work clusters from the current extractions.
     *
     * <p>Replaces rather than appends: clusters are a pure function of the
     * extraction rows, so keeping stale ones from a previous run would inflate
     * the consensus counts they exist to report.
     */
    @Transactional
    public List<FutureWorkClusterEntity> mine() {
        List<ExtractionEntity> withFutureWork = extractions.findAll().stream()
                .filter(e -> e.getFutureWorkSuggested() != null && !e.getFutureWorkSuggested().isBlank())
                .toList();

        log.info("Future-work mining: {} of {} papers state a future direction",
                withFutureWork.size(), extractions.count());

        if (withFutureWork.size() < config.minPapersPerConstruct()) {
            log.info("Not enough future-work statements to cluster");
            clusters.deleteAll();
            return List.of();
        }

        List<float[]> vectors = embeddings.embedAll(
                withFutureWork.stream().map(ExtractionEntity::getFutureWorkSuggested).toList());

        List<List<Integer>> groups = cluster(vectors, config.futureWorkSimilarityThreshold());

        clusters.deleteAll();
        List<FutureWorkClusterEntity> saved = new ArrayList<>();
        for (List<Integer> group : groups) {
            if (group.size() < config.minPapersPerConstruct()) {
                // A lone paper's future-work note is not consensus, and
                // surfacing it as a "theme" would overstate the evidence.
                continue;
            }
            List<Long> paperIds = group.stream()
                    .map(i -> withFutureWork.get(i).getPaper().getId())
                    .distinct()
                    .toList();
            if (paperIds.size() < config.minPapersPerConstruct()) {
                // Two statements from the same paper are not two papers agreeing.
                continue;
            }
            saved.add(clusters.save(new FutureWorkClusterEntity(themeFor(group, withFutureWork), paperIds)));
        }

        log.info("Future-work mining: {} cluster(s) from {} statement(s)",
                saved.size(), withFutureWork.size());
        return saved;
    }

    // --------------------------------------------------------------- clustering

    /**
     * Greedy single-pass clustering by cosine similarity.
     *
     * <p>Greedy rather than k-means or DBSCAN for two reasons: the number of
     * clusters is unknown and should not be guessed, and the threshold is
     * already the decision boundary the thesis cares about. A statement joins
     * the first existing cluster it is close enough to, otherwise it seeds a
     * new one.
     *
     * <p>Greedy is order-dependent, so the input is processed in a stable order
     * (as returned by the repository) and the result is deterministic for a
     * fixed corpus.
     *
     * @return index groups into the input list
     */
    static List<List<Integer>> cluster(List<float[]> vectors, double threshold) {
        List<List<Integer>> groups = new ArrayList<>();

        for (int i = 0; i < vectors.size(); i++) {
            float[] vector = vectors.get(i);
            List<Integer> best = null;
            double bestSimilarity = threshold;

            for (List<Integer> group : groups) {
                // Compare against the seed (first member) rather than the
                // centroid: a centroid drifts as a group grows and can end up
                // merging two themes that only met in the middle.
                double similarity = cosine(vectors.get(group.get(0)), vector);
                if (similarity >= bestSimilarity) {
                    bestSimilarity = similarity;
                    best = group;
                }
            }

            if (best == null) {
                List<Integer> group = new ArrayList<>();
                group.add(i);
                groups.add(group);
            }
            else {
                best.add(i);
            }
        }
        return groups;
    }

    /** Cosine similarity in [-1,1]; 1 means identical direction. */
    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // ------------------------------------------------------------------ themes

    /**
     * Picks a cluster's label without an LLM call.
     *
     * <p>Uses the shortest statement in the group. The shortest is almost
     * always the most general - the longer members of a cluster tend to be the
     * same idea plus a specific caveat - and, unlike a generated summary, it is
     * guaranteed to be a sentence some paper actually wrote.
     */
    private String themeFor(List<Integer> group, List<ExtractionEntity> sources) {
        return group.stream()
                .map(i -> sources.get(i).getFutureWorkSuggested())
                .min(Comparator.comparingInt(String::length))
                .orElse("(unlabelled theme)");
    }

    // ------------------------------------------------------------------- reads

    /** Clusters ordered by consensus strength, strongest first. */
    public List<FutureWorkClusterEntity> findAll() {
        return clusters.findAllByOrderByPaperCountDesc();
    }

    /**
     * Clusters as API DTOs, with paper ids resolved to citekeys.
     *
     * <p>The citekey lookup is built once for the whole list rather than per
     * cluster - resolving each cluster independently would re-read the
     * extraction table once per cluster.
     */
    public List<FutureWorkCluster> asDtos() {
        Map<Long, String> citekeyByPaperId = extractions.findAll().stream()
                .collect(Collectors.toMap(e -> e.getPaper().getId(),
                        e -> e.getPaper().getCitekey(), (a, b) -> a));

        return findAll().stream()
                .map(cluster -> FutureWorkCluster.from(cluster,
                        cluster.getPaperIds().stream()
                                .map(citekeyByPaperId::get)
                                .filter(Objects::nonNull)
                                .sorted()
                                .toList()))
                .toList();
    }
}
