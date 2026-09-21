package org.thesis.research.litreview.service.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.thesis.research.litreview.config.LitreviewProperties;
import org.thesis.research.litreview.config.synthesis.EmbeddingConfig.EmbeddingBatcher;
import org.thesis.research.litreview.entity.ChunkEntity;
import org.thesis.research.litreview.entity.ChunkEntityRepository;
import org.thesis.research.litreview.entity.ChunkJdbcRepository;
import org.thesis.research.litreview.entity.ChunkJdbcRepository.HybridHit;

/**
 * Retrieval facade: embed the query, run the fused SQL, hydrate the hits.
 *
 * <p>The split of responsibility is deliberate. {@link ChunkJdbcRepository}
 * owns ranking (it is the only thing that can see both indexes at once); this
 * class owns the two things ranking cannot know - turning text into a vector,
 * and turning chunk rows into something citable.
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private final ChunkJdbcRepository chunkJdbc;
    private final ChunkEntityRepository chunks;
    private final EmbeddingBatcher embeddings;
    private final LitreviewProperties.Search config;

    public HybridSearchService(ChunkJdbcRepository chunkJdbc, ChunkEntityRepository chunks,
            EmbeddingBatcher embeddings, LitreviewProperties props) {
        this.chunkJdbc = chunkJdbc;
        this.chunks = chunks;
        this.embeddings = embeddings;
        this.config = props.search();
    }

    /**
     * @return results best-first by fused score; empty when the query is blank
     *         or nothing in the corpus matches
     */
    public List<SearchResult> search(SearchRequest request) {
        if (request == null || !request.hasQuery()) {
            return List.of();
        }

        int topK = request.topK() == null ? config.defaultTopK() : request.topK();
        float[] queryVector = embeddings.embedOne(request.query());

        List<HybridHit> hits = chunkJdbc.hybridSearch(
                queryVector, request.query(), topK, request.sections(), request.paperIds());

        return hydrate(hits);
    }

    /**
     * Convenience for internal callers (steelman, gap hints) that just want
     * evidence for a sentence.
     */
    public List<SearchResult> search(String query, int topK) {
        return search(SearchRequest.of(query, topK));
    }

    /**
     * Turns ranked ids into ordered results.
     *
     * <p>Reorders in memory rather than relying on {@code IN (...)} order,
     * which Postgres does not guarantee. Hits whose chunk has since been
     * deleted are dropped silently - a concurrent re-ingest is not an error
     * worth failing a search over.
     */
    private List<SearchResult> hydrate(List<HybridHit> hits) {
        if (hits.isEmpty()) {
            return List.of();
        }

        List<Long> ids = hits.stream().map(HybridHit::chunkId).toList();
        Map<Long, ChunkEntity> byId = new LinkedHashMap<>();
        chunks.findByIdIn(ids).forEach(c -> byId.put(c.getId(), c));

        List<SearchResult> results = hits.stream()
                .filter(hit -> byId.containsKey(hit.chunkId()))
                .map(hit -> SearchResult.from(byId.get(hit.chunkId()), hit.score()))
                .toList();

        if (results.size() < hits.size()) {
            log.debug("Dropped {} hybrid hits whose chunk no longer exists",
                    hits.size() - results.size());
        }
        return results;
    }
}
