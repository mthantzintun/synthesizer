package org.thesis.research.litreview.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Single source of truth for every tunable in the pipeline. Bound from the
 * {@code litreview.*} block of application.yaml.
 */
@ConfigurationProperties(prefix = "litreview")
public record LitreviewProperties(

        /** Location of the BibTeX library. Accepts {@code classpath:}, {@code file:} or a bare path. */
        @DefaultValue("classpath:data/library.bib") String bibFile,

        /** Folder scanned recursively to resolve the {@code file = {...}} attachments. */
        @DefaultValue("src/main/resources/papers") String pdfRoot,

        /** Where GROBID TEI XML responses are cached on disk. */
        @DefaultValue(".cache/tei") String teiCacheDir,

        /** Must match the embedding model and the {@code chunk.embedding} column. */
        @DefaultValue("384") int embeddingDimension,

        @DefaultValue Grobid grobid,
        @DefaultValue Chunking chunking,
        @DefaultValue Search search,
        @DefaultValue Synthesis synthesis) {

    public record Grobid(
            @DefaultValue("http://localhost:8070") String url,
            @DefaultValue("5m") Duration timeout,
            @DefaultValue("true") boolean consolidateHeader,
            @DefaultValue("false") boolean consolidateCitations,
            @DefaultValue("true") boolean segmentSentences,
            @DefaultValue("2") int maxRetries) {
    }

    public record Chunking(
            @DefaultValue("1200") int targetChars,
            @DefaultValue("1800") int maxChars,
            @DefaultValue("250") int minChars,
            @DefaultValue("150") int overlapChars,
            @DefaultValue({ "references", "bibliography", "acknowledgement", "acknowledgements",
                    "appendix", "author contributions", "conflicts of interest", "funding" }) List<String> skipSections) {

        /** Lower-cased for case-insensitive heading matching. */
        public boolean isSkippable(String heading) {
            if (heading == null || heading.isBlank()) {
                return false;
            }
            String normalized = heading.toLowerCase().trim();
            return skipSections.stream().anyMatch(normalized::startsWith);
        }
    }

    public record Search(
            @DefaultValue("12") int defaultTopK,
            @DefaultValue("60") int candidatePool,
            /** The {@code k} constant in Reciprocal Rank Fusion; 60 is the value from the original paper. */
            @DefaultValue("60") int rrfK) {
    }

    public record Synthesis(
            @DefaultValue("0.62") double futureWorkSimilarityThreshold,
            @DefaultValue("2") int minPapersPerConstruct) {
    }
}

