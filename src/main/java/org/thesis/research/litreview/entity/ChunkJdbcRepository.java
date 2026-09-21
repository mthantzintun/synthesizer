package org.thesis.research.litreview.entity;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.thesis.research.litreview.config.LitreviewProperties;
import org.thesis.research.litreview.util.Chunk;
import org.thesis.research.litreview.venue.grobid.TeiDocument;

/**
 * Owns the two columns of {@code chunk} that Hibernate cannot map: the
 * pgvector {@code embedding} and the generated {@code tsv}.
 *
 * <p>{@link ChunkEntity} handles ordinary relational reads. Everything that
 * touches the vector lives here, because:
 * <ul>
 *   <li>Hibernate has no built-in pgvector type;</li>
 *   <li>{@code tsv} is {@code GENERATED ALWAYS} - any ORM write to it is an
 *       error, so it must stay out of the entity entirely;</li>
 *   <li>hybrid retrieval is one SQL statement over both indexes, which is
 *       awkward to express in JPQL and trivial to express in SQL.</li>
 * </ul>
 *
 * <p><b>How vectors are bound.</b> As a pgvector text literal with an explicit
 * cast - {@code CAST(:embedding AS vector)} - rather than via the {@code PGvector}
 * class from the pgvector-java library. That class only works if the driver
 * type is registered first ({@code PGConnection.addDataType}), and the
 * registration cannot be done from a Spring bean: Hikari seals its config once
 * the pool has started, so {@code setConnectionInitSql} throws. The
 * text-literal form needs no registration, no pool hook and no library, and it
 * is what the server would have parsed the other form into anyway.
 *
 * <p>{@code CAST(... AS vector)} is used rather than the shorter
 * {@code :embedding::vector}: the {@code ::} form is ambiguous to Spring's
 * named-parameter parser, which reads {@code :name} greedily. The {@code CAST}
 * form leaves nothing to interpret.
 */
@Repository
public class ChunkJdbcRepository {

    private static final Logger log = LoggerFactory.getLogger(ChunkJdbcRepository.class);

    /**
     * Note the explicit {@code ::vector} cast: the bound value is a string, and
     * without the cast Postgres rejects it as an unknown-typed literal.
     */
    private static final String INSERT_SQL = """
            INSERT INTO chunk (paper_id, section, canonical_section, ordinal, page, content, embedding)
            VALUES (?, ?, ?, ?, ?, ?, ?::vector)
            """;

    /**
     * Two independent retrieval arms fused by Reciprocal Rank Fusion.
     *
     * <p>Each arm ranks the whole filtered corpus and is capped at
     * {@code :pool} rows; RRF then scores a row by {@code 1/(k + rank)} in
     * each arm and sums. RRF is used instead of score normalisation because
     * cosine distance and {@code ts_rank} are not on comparable scales - any
     * weighted blend of the two would need a tuned per-corpus constant, while
     * RRF only needs ranks and works out of the box.
     *
     * <p>{@code <=>} is pgvector's cosine <em>distance</em> (0 = identical),
     * so ascending order is best-first. The text arm uses {@code websearch_to_tsquery}
     * rather than {@code plainto_tsquery}: it tolerates quoted phrases and
     * {@code OR} the way a user types them, and degrades to the same behaviour
     * for a bare keyword list.
     */
    private static final String HYBRID_SQL = """
            WITH vector_arm AS (
                SELECT c.id,
                       row_number() OVER (ORDER BY c.embedding <=> CAST(:embedding AS vector)) AS rank
                FROM chunk c
                WHERE c.embedding IS NOT NULL
                  AND (:filter_sections = FALSE OR c.canonical_section IN (:sections))
                  AND (:filter_papers = FALSE OR c.paper_id IN (:paper_ids))
                ORDER BY c.embedding <=> CAST(:embedding AS vector)
                LIMIT :pool
            ),
            text_arm AS (
                SELECT c.id,
                       row_number() OVER (
                           ORDER BY ts_rank(c.tsv, websearch_to_tsquery('english', :query)) DESC
                       ) AS rank
                FROM chunk c
                WHERE c.tsv @@ websearch_to_tsquery('english', :query)
                  AND (:filter_sections = FALSE OR c.canonical_section IN (:sections))
                  AND (:filter_papers = FALSE OR c.paper_id IN (:paper_ids))
                ORDER BY ts_rank(c.tsv, websearch_to_tsquery('english', :query)) DESC
                LIMIT :pool
            )
            SELECT id,
                   SUM(1.0 / (:rrf_k + rank)) AS score
            FROM (
                SELECT id, rank FROM vector_arm
                UNION ALL
                SELECT id, rank FROM text_arm
            ) fused
            GROUP BY id
            ORDER BY score DESC
            LIMIT :top_k
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final LitreviewProperties.Search searchConfig;

    public ChunkJdbcRepository(NamedParameterJdbcTemplate jdbc, LitreviewProperties props) {
        this.jdbc = jdbc;
        this.searchConfig = props.search();
    }

    // ---------------------------------------------------------------- writes

    /**
     * Replaces a paper's chunks: delete then insert, in one transaction.
     *
     * <p>This is the only entry point for writing chunks, and it writes the
     * whole row - including the embedding - through plain JDBC rather than
     * letting JPA insert the relational columns and then updating the vector
     * separately. Two writers for one row is how a chunk ends up existing with
     * a null embedding: invisible to the vector arm, silently narrowing
     * retrieval, and very hard to notice. {@link ChunkEntity} therefore stays
     * read-only in practice, and its doc comment says so.
     *
     * <p>Delete-then-insert is atomic here rather than two calls from the
     * caller so that a failure between them cannot leave a paper with no
     * chunks at all.
     *
     * @param paperId    owning paper (already persisted, so its id is known)
     * @param chunks     chunks in ordinal order
     * @param embeddings one vector per chunk, same order and length
     */
    @Transactional
    public void replaceBatch(Long paperId, List<Chunk> chunks, List<float[]> embeddings) {
        jdbc.update("DELETE FROM chunk WHERE paper_id = :paperId", Map.of("paperId", paperId));

        if (chunks.isEmpty()) {
            return;
        }
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException(
                    "chunks (%d) and embeddings (%d) must be the same size"
                            .formatted(chunks.size(), embeddings.size()));
        }

        jdbc.getJdbcTemplate().batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Chunk chunk = chunks.get(i);
                ps.setLong(1, paperId);
                ps.setString(2, chunk.section());
                // canonical_section is a TEXT column; write the enum name
                ps.setString(3, chunk.canonical() == null ? null : chunk.canonical().name());
                ps.setInt(4, chunk.ordinal());
                if (chunk.page() == null) {
                    ps.setNull(5, java.sql.Types.INTEGER);
                }
                else {
                    ps.setInt(5, chunk.page());
                }
                ps.setString(6, chunk.text());
                ps.setString(7, toVectorLiteral(embeddings.get(i)));
            }

            @Override
            public int getBatchSize() {
                return chunks.size();
            }
        });

        log.debug("Wrote {} chunk rows with embeddings for paper {}", chunks.size(), paperId);
    }

    /**
     * Removes a paper's chunks, including their embeddings.
     *
     * <p>Exposed for the case where a paper should be left with no chunks at
     * all (e.g. it failed re-parsing); normal re-ingest goes through
     * {@link #replaceBatch}, which does not need a separate delete.
     */
    @Transactional
    public int deleteByPaperId(Long paperId) {
        return jdbc.update("DELETE FROM chunk WHERE paper_id = :paperId",
                Map.of("paperId", paperId));
    }

    // ---------------------------------------------------------------- search

    /**
     * Runs the fused hybrid query.
     *
     * @param queryEmbedding query vector, same width as {@code chunk.embedding}
     * @param queryText      raw user query for the full-text arm
     * @param topK           rows to return after fusion
     * @param sections       canonical-section filter; empty means no filter
     * @param paperIds       paper filter; empty means no filter
     * @return chunk ids with their fused score, best first
     */
    public List<HybridHit> hybridSearch(float[] queryEmbedding, String queryText, int topK,
            List<TeiDocument.CanonicalSection> sections, List<Long> paperIds) {

        List<String> sectionNames = sections == null ? List.of()
                : sections.stream().filter(Objects::nonNull).map(Enum::name).toList();
        List<Long> paperIdList = paperIds == null ? List.of()
                : paperIds.stream().filter(Objects::nonNull).toList();

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("embedding", toVectorLiteral(queryEmbedding))
                .addValue("query", queryText == null ? "" : queryText)
                .addValue("pool", searchConfig.candidatePool())
                .addValue("rrf_k", searchConfig.rrfK())
                .addValue("top_k", topK)
                // The IN (:list) branches are guarded by these flags because an
                // empty IN list is a syntax error in Postgres, and building the
                // WHERE clause dynamically would mean maintaining two copies of
                // a statement that is already the subtlest SQL in the project.
                .addValue("filter_sections", !sectionNames.isEmpty())
                .addValue("sections", sectionNames.isEmpty() ? List.of("__none__") : sectionNames)
                .addValue("filter_papers", !paperIdList.isEmpty())
                .addValue("paper_ids", paperIdList.isEmpty() ? List.of(-1L) : paperIdList);

        List<HybridHit> hits = jdbc.query(HYBRID_SQL, params, (rs, rowNum) ->
                new HybridHit(rs.getLong("id"), rs.getDouble("score")));

        log.debug("Hybrid search '{}' -> {} hits (topK={}, pool={})",
                queryText, hits.size(), topK, searchConfig.candidatePool());
        return hits;
    }

    /**
     * Counts, per canonical section, how many papers mention a construct.
     *
     * <p>Coverage is measured over the chunk table rather than the extraction
     * table on purpose: extraction records a construct once, for the paper as a
     * whole, so it cannot say <em>where</em> in the paper a construct is
     * discussed. The gap that matters most - a construct raised in
     * introductions but never tested in a results section - is only visible
     * per section, which is what chunks carry.
     *
     * <p>Matching is a case-insensitive substring on the construct's own words
     * with word-boundary anchors, not a full-text query: construct labels are
     * short noun phrases extracted from the papers themselves, and stemming
     * them ("visibilit" matching "visible") would create false coverage.
     *
     * @param construct the construct label to look for
     * @return one row per canonical section that has any coverage
     */
    public List<CoverageRow> constructCoverage(String construct) {
        String pattern = "%" + escapeLike(construct) + "%";
        String sql = """
                SELECT c.canonical_section AS section,
                       COUNT(DISTINCT c.paper_id) AS paper_count,
                       array_agg(DISTINCT p.citekey ORDER BY p.citekey) AS citekeys
                FROM chunk c
                JOIN paper p ON p.id = c.paper_id
                WHERE c.canonical_section IS NOT NULL
                  AND c.content ILIKE :pattern ESCAPE '\\'
                GROUP BY c.canonical_section
                ORDER BY c.canonical_section
                """;

        return jdbc.query(sql, Map.of("pattern", pattern), (rs, rowNum) -> new CoverageRow(
                rs.getString("section"),
                rs.getInt("paper_count"),
                List.of((String[]) rs.getArray("citekeys").getArray())));
    }

    /**
     * Renders a vector as pgvector's text form: {@code [0.1,0.2,0.3]}.
     *
     * <p>Bound as a string and cast by the SQL ({@code ?::vector} on insert,
     * {@code CAST(:embedding AS vector)} in the search CTE). pgvector accepts
     * exactly this format, and going through text avoids needing the driver
     * type registered - which, as the class javadoc explains, cannot be done
     * from a Spring bean.
     *
     * <p>{@code Float.toString} is used rather than a {@code %f} format so no
     * precision is lost: a truncated component would silently shift every
     * cosine distance.
     */
    static String toVectorLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("Embedding vector must not be empty");
        }
        StringBuilder sb = new StringBuilder(vector.length * 8).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(vector[i]));
        }
        return sb.append(']').toString();
    }

    /**
     * Escapes LIKE metacharacters in user/extracted text.
     *
     * <p>Constructs are model-extracted and can legitimately contain {@code %}
     * or {@code _}; unescaped they would turn a specific lookup into a wildcard
     * match and inflate coverage.
     */
    static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Papers covering one construct within one canonical section. */
    public record CoverageRow(String canonicalSection, int paperCount, List<String> citekeys) {
    }

    /**
     * Fuses two ranked id lists into RRF scores, without touching the database.
     *
     * <p>Extracted so the ranking maths is unit-testable in isolation - the SQL
     * above is the same formula, and the two must not drift.
     *
     * @param vectorRanks chunk ids in vector-arm order, best first
     * @param textRanks   chunk ids in text-arm order, best first
     * @param rrfK        the {@code k} constant; 60 is the value from the RRF paper
     * @return fused scores keyed by chunk id
     */
    public static Map<Long, Double> fuse(List<Long> vectorRanks, List<Long> textRanks, int rrfK) {
        Map<Long, Double> scores = new HashMap<>();
        addRanks(scores, vectorRanks, rrfK);
        addRanks(scores, textRanks, rrfK);
        return scores;
    }

    private static void addRanks(Map<Long, Double> scores, List<Long> ranks, int rrfK) {
        for (int i = 0; i < ranks.size(); i++) {
            // rank is 1-based, matching row_number() in the SQL
            scores.merge(ranks.get(i), 1.0 / (rrfK + i + 1), Double::sum);
        }
    }

    /** Orders fused scores best-first, breaking ties by id for determinism. */
    public static List<Long> rankByScore(Map<Long, Double> fused) {
        List<Long> ordered = new ArrayList<>(fused.keySet());
        ordered.sort((a, b) -> {
            int byScore = Double.compare(fused.get(b), fused.get(a));
            return byScore != 0 ? byScore : Long.compare(a, b);
        });
        return ordered;
    }

    /** One retrieved chunk and its fused score. */
    public record HybridHit(long chunkId, double score) {
    }
}
