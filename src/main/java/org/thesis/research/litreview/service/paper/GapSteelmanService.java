package org.thesis.research.litreview.service.paper;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thesis.research.litreview.entity.ApiCallLogEntity;
import org.thesis.research.litreview.entity.ApiCallLogRepository;
import org.thesis.research.litreview.service.retrieval.HybridSearchService;
import org.thesis.research.litreview.service.retrieval.SearchResult;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Attacks a proposed research gap before anyone else can.
 *
 * <p>A gap claim is only as good as its answer to "why might this not be a gap
 * after all?" - the most common way a thesis gap statement collapses in a viva
 * is that a supervisor knows a paper, or an obvious reason, that already covers
 * it. This service builds the strongest possible case against the proposed gap,
 * grounded in the corpus, so the student can either rebut it or drop the claim
 * before it is challenged.
 *
 * <p>Retrieval is deliberate about what it feeds the model: the gap statement
 * itself is used as the query, so the evidence returned is the closest the
 * corpus comes to already addressing it. If the corpus genuinely does not cover
 * the gap, the retrieval returns weak material and the model is instructed to
 * say so rather than manufacture an objection.
 */
@Service
public class GapSteelmanService {

    private static final Logger log = LoggerFactory.getLogger(GapSteelmanService.class);

    /**
     * Evidence chunks to retrieve. Higher than the interactive search default
     * because the cost of a missed counter-example is a gap claim that survives
     * only until someone else finds it.
     */
    private static final int EVIDENCE_TOP_K = 24;

    private final ChatClient synthesisClient;
    private final HybridSearchService search;
    private final ApiCallLogRepository apiLog;
    private final String modelName;

    public GapSteelmanService(@Qualifier("synthesisClient") ChatClient synthesisClient,
            HybridSearchService search, ApiCallLogRepository apiLog,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String modelName) {
        this.synthesisClient = synthesisClient;
        this.search = search;
        this.apiLog = apiLog;
        this.modelName = modelName;
    }

    /**
     * @param proposedGap the gap statement to attack, e.g. "no study has
     *                    measured the effect of parcel lockers on last-mile
     *                    emissions in dense European cities"
     * @return the counter-argument, the evidence it used, and whether the
     *         corpus was strong enough to mount one at all
     */
    public Steelman steelman(String proposedGap) {
        if (proposedGap == null || proposedGap.isBlank()) {
            throw new IllegalArgumentException("proposedGap must not be blank");
        }

        List<SearchResult> evidence = search.search(proposedGap, EVIDENCE_TOP_K);
        log.info("Steelman: retrieved {} evidence chunk(s) for the proposed gap", evidence.size());

        if (evidence.isEmpty()) {
            // Nothing to ground an argument in. Saying so is the honest answer,
            // and it is also weak positive evidence for the gap itself.
            return new Steelman(proposedGap, false,
                    "The corpus contains no material close enough to this gap to mount a "
                            + "grounded counter-argument. This is weak evidence that the gap "
                            + "may be real, but it is not proof - the library may simply not "
                            + "cover the area.",
                    List.of(), List.of());
        }

        ResponseEntity<ChatResponse, SteelmanAnswer> response;
        try {
            response = synthesisClient.prompt()
                    .user(steelmanPrompt(proposedGap, evidence))
                    .call()
                    .responseEntity(SteelmanAnswer.class);
        }
        catch (RuntimeException e) {
            logBestEffort(ApiCallLogEntity.failure(null, "steelman", modelName, e.getMessage()));
            throw new SteelmanException("Steelman call failed", e);
        }

        recordUsage(response.response());

        SteelmanAnswer answer = response.entity();
        if (answer == null) {
            throw new SteelmanException("Model returned nothing parseable");
        }

        List<String> citekeys = evidence.stream()
                .map(SearchResult::citekey)
                .distinct()
                .sorted()
                .toList();

        return new Steelman(proposedGap, answer.gapSurvives(),
                answer.counterArgument(), citekeys, evidence);
    }

    private String steelmanPrompt(String proposedGap, List<SearchResult> evidence) {
        String block = evidence.stream()
                .map(r -> "%s %s%n%s%n".formatted(r.citationMarker(),
                        r.title() == null ? "" : "(" + r.title() + ")", r.content()))
                .collect(Collectors.joining("\n"));

        return """
                A researcher proposes this gap in the literature:

                "%s"

                Your task is to construct the STRONGEST possible argument that this gap is
                NOT real - that the literature already addresses it, that it is answered by
                something adjacent, or that the claim is narrower than it appears.

                Rules:
                - Ground every point in the evidence below and cite the [CITEKEY] markers.
                - Do not invent papers, findings or citations.
                - If the evidence does not support a strong objection, say so plainly and set
                  gapSurvives to true. A weak objection is worse than none, because it gives
                  false confidence that the gap has been tested.
                - Be specific about what the evidence does and does not cover.

                --- BEGIN EVIDENCE ---
                %s
                --- END EVIDENCE ---
                """.formatted(proposedGap, block);
    }

    private void recordUsage(ChatResponse response) {
        Integer in = null;
        Integer out = null;
        if (response != null && response.getMetadata() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                in = usage.getPromptTokens();
                out = usage.getCompletionTokens();
            }
        }
        logBestEffort(ApiCallLogEntity.success(null, "steelman", modelName, in, out));
    }

    private void logBestEffort(ApiCallLogEntity entry) {
        try {
            apiLog.save(entry);
        }
        catch (RuntimeException e) {
            log.warn("Could not write api_call_log row: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ types

    /**
     * The outcome of a steelman attempt.
     *
     * @param proposedGap    the claim that was attacked
     * @param gapSurvives    true when no strong counter-argument could be grounded
     * @param counterArgument the strongest objection, or an explanation of why none was found
     * @param citekeys       papers the counter-argument cites
     * @param evidence       the retrieved chunks, for the researcher to read directly
     */
    public record Steelman(
            String proposedGap,
            boolean gapSurvives,
            String counterArgument,
            List<String> citekeys,
            List<SearchResult> evidence) {
    }

    /** Structured output for the steelman call. */
    @JsonClassDescription("A grounded counter-argument against a proposed research gap.")
    public record SteelmanAnswer(
            @JsonPropertyDescription("The strongest evidence-grounded argument that the gap is not real. "
                    + "If no strong argument exists, explain that instead.")
            String counterArgument,

            @JsonPropertyDescription("True if the gap withstands the objection, i.e. no strong "
                    + "counter-argument could be grounded in the evidence. False if the objection holds.")
            boolean gapSurvives) {
    }

    /** The steelman call could not be completed. */
    public static class SteelmanException extends RuntimeException {
        public SteelmanException(String message) {
            super(message);
        }

        public SteelmanException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
