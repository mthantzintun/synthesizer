package org.thesis.research.litreview.service.extraction;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * The structured concept-matrix row for one paper - the LLM's output type.
 *
 * <p>Kept as a plain record, separate from {@link
 * org.thesis.research.litreview.entity.ExtractionEntity}, so the shape the
 * model is asked to produce is independent of the table that stores it. The
 * field descriptions below are not documentation for humans: Spring AI turns
 * them into the JSON schema handed to the model, and they are the main lever
 * against the failure mode this pipeline cares about most - a plausible
 * invention filling a field the paper never actually addressed.
 *
 * <p>Every field is nullable by design. A paper that does not state its
 * theoretical lens must come back {@code null}, and the prompt says so
 * explicitly; a required field would pressure the model into guessing.
 */
@JsonClassDescription("A concept-matrix row extracted from one paper. "
        + "Every field must be grounded in the paper's own text.")
public record Extraction(

        @JsonPropertyDescription("The paper's research question(s), stated as the paper states them. "
                + "Null if the paper does not articulate one explicitly.")
        String researchQuestion,

        @JsonPropertyDescription("The theoretical framework or lens the paper draws on "
                + "(e.g. 'TOE framework', 'resource-based view'). Null if none is named.")
        String theoreticalLens,

        @JsonPropertyDescription("The research method or design (e.g. 'systematic literature review', "
                + "'discrete-event simulation', 'multiple case study'). Null if unclear.")
        String method,

        @JsonPropertyDescription("Key constructs or variables the paper studies, using the paper's own "
                + "vocabulary. Empty list if the paper names none.")
        List<String> constructs,

        @JsonPropertyDescription("The paper's central finding, in one or two sentences, "
                + "stated as the paper states it.")
        String keyFinding,

        @JsonPropertyDescription("A limitation the paper explicitly acknowledges. "
                + "Null if the paper states none - do not infer limitations.")
        String statedLimitation,

        @JsonPropertyDescription("Future research the paper explicitly calls for. "
                + "Null if the paper suggests none - do not infer directions.")
        String futureWorkSuggested,

        @JsonPropertyDescription("The empirical context: sector, country or setting "
                + "(e.g. 'urban freight, EU', 'e-commerce logistics, China').")
        String contextSector,

        @JsonPropertyDescription("A verbatim sentence from the paper supporting keyFinding. "
                + "Copy it exactly, do not paraphrase or correct it.")
        String supportingQuote,

        @JsonPropertyDescription("The page number the supportingQuote appears on, as a string. "
                + "Null if unknown.")
        String quotePage,

        @JsonPropertyDescription("Leave null; the pipeline records the model identifier itself.")
        String modelUsed) {

    /**
     * Repairs the one field the model should not be trusted with.
     *
     * <p>{@code modelUsed} is provenance, and provenance that the model writes
     * about itself is worthless - it will confidently name a model it was not
     * run on. The pipeline overwrites it with the configured identifier.
     */
    public Extraction withModelUsed(String actualModel) {
        return new Extraction(researchQuestion, theoreticalLens, method, constructs, keyFinding,
                statedLimitation, futureWorkSuggested, contextSector, supportingQuote, quotePage,
                actualModel);
    }
}
