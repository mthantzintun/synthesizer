package org.thesis.research.litreview.config.synthesis;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thesis.research.litreview.config.LitreviewProperties;

/**
 * Wires the local ONNX embedding model into a batch-friendly helper.
 *
 * <p>Which {@link EmbeddingModel} bean is injected here is decided by
 * {@code spring.ai.model.embedding} - set to {@code transformers} in
 * application.yaml. Do not remove that property: both the OpenAI and the
 * transformers autoconfigurations are {@code matchIfMissing = true}, so
 * leaving it unset is a coin flip that could silently start billing a remote
 * embedding endpoint.
 */
@Configuration
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    /**
     * Thin wrapper that turns "embed N strings" into a single batched ONNX
     * call. ONNX Runtime is far more efficient on a batch than on N separate
     * invocations - for a 27-paper corpus that is the difference between one
     * padded tensor per paper and thousands of individual sessions.
     */
    @Bean
    EmbeddingBatcher embeddingBatcher(EmbeddingModel model, LitreviewProperties props) {
        log.info("Embedding batcher ready (model={}, expected dimension={})",
                model.getClass().getSimpleName(), props.embeddingDimension());
        return new EmbeddingBatcher(model, props.embeddingDimension());
    }

    /** Batched embedding with a dimension guard against a mis-configured model. */
    public static class EmbeddingBatcher {

        private final EmbeddingModel model;
        private final int expectedDimension;

        EmbeddingBatcher(EmbeddingModel model, int expectedDimension) {
            this.model = model;
            this.expectedDimension = expectedDimension;
        }

        /** Embeds one string. Used for query-side embedding in hybrid search. */
        public float[] embedOne(String text) {
            float[] vector = model.embed(text);
            checkDimension(vector, 1);
            return vector;
        }

        /**
         * Embeds many strings, preserving input order.
         *
         * @throws IllegalArgumentException if the model returns a different
         *         number of vectors than it was given, which would silently
         *         mis-align chunks with their embeddings
         */
        public List<float[]> embedAll(List<String> texts) {
            if (texts.isEmpty()) {
                return List.of();
            }
            List<float[]> vectors = model.embed(texts);
            if (vectors.size() != texts.size()) {
                throw new IllegalArgumentException(
                        "Embedding model returned %d vectors for %d inputs"
                                .formatted(vectors.size(), texts.size()));
            }
            vectors.forEach(v -> checkDimension(v, texts.size()));
            return vectors;
        }

        private void checkDimension(float[] vector, int batchSize) {
            if (vector.length != expectedDimension) {
                // Writing a vector of the wrong width into VECTOR(384) fails at
                // the database with an opaque message, so fail here instead,
                // where the cause (wrong model, wrong chunk.embedding width) is
                // still obvious.
                throw new IllegalStateException(
                        ("Embedding model produced %d dimensions but litreview.embedding-dimension is %d "
                                + "(batch of %d). Check that spring.ai.model.embedding is 'transformers'.")
                                .formatted(vector.length, expectedDimension, batchSize));
            }
        }
    }
}
