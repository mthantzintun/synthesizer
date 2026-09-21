package org.thesis.research.litreview.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Two ChatClients with deliberately different temperatures.
 *
 * <p>Extraction must be reproducible and must never invent content, so it runs
 * at temperature 0. Synthesis (clustering themes, phrasing a gap statement) is
 * a generative task where a little variation produces better prose, so it runs
 * warmer.
 */
@Configuration
public class ChatModelConfig {

    /**
     * Deterministic, quote-grounded extraction. Anything the model cannot find
     * verbatim must come back null rather than be filled in from world
     * knowledge - that is the difference between a defensible concept matrix
     * and a fabricated one.
     */
    @Bean("extractionClient")
    ChatClient extractionClient(ChatModel model) {
        return ChatClient.builder(model)
                .defaultOptions(OpenAiChatOptions.builder().temperature(0.0))
                .defaultSystem("""
                        You are a meticulous research assistant building a literature concept matrix.

                        Rules, in priority order:
                        1. Extract ONLY what the paper explicitly states. Never infer, never generalise,
                           never fill gaps from your own knowledge of the field.
                        2. If the paper does not state something, return null for that field.
                           A null is a correct answer. A plausible guess is a wrong answer.
                        3. Every claim you record must be traceable to a verbatim quote from the text.
                        4. Quote exactly - do not paraphrase inside quotation marks, do not fix typos.
                        5. Use the paper's own vocabulary for constructs; do not substitute synonyms.
                        """)
                .build();
    }

    /** Warmer client for clustering, contrast and gap prose. */
    @Bean("synthesisClient")
    ChatClient synthesisClient(ChatModel model) {
        return ChatClient.builder(model)
                .defaultOptions(OpenAiChatOptions.builder().temperature(0.3))
                .defaultSystem("""
                        You are a critical reviewer synthesising across a corpus of papers.
                        Ground every statement in the supplied evidence and cite the citekeys you used.
                        If the evidence is too thin to support a conclusion, say so plainly
                        instead of hedging your way to a claim.
                        """)
                .build();
    }
}
