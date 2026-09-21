package org.thesis.research.litreview.service.synthesis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thesis.research.litreview.config.LitreviewProperties;
import org.thesis.research.litreview.entity.ApiCallLogEntity;
import org.thesis.research.litreview.entity.ApiCallLogRepository;
import org.thesis.research.litreview.entity.ContradictionEntity;
import org.thesis.research.litreview.entity.ContradictionRepository;
import org.thesis.research.litreview.entity.ExtractionEntity;
import org.thesis.research.litreview.entity.Paper;
import org.thesis.research.litreview.service.extraction.ExtractionRepository;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Finds genuine disagreements between papers that study the same construct.
 *
 * <p>Grouping happens first, in SQL: only constructs with at least
 * {@code litreview.synthesis.min-papers-per-construct} papers are considered.
 * That is both a cost control and a correctness one - a "contradiction" needs
 * two sides, and asking the model to find disagreement in a single paper's
 * findings is asking it to invent one.
 *
 * <p>Re-running is additive, never destructive. Existing rows carry a human's
 * {@code reviewed} flag and {@code verdict}, and that judgement is the entire
 * reason contradictions are persisted rather than recomputed per request.
 * Deleting them to refresh a finding list would throw away the only part of
 * this table a person typed. A pair already recorded for a construct is
 * therefore skipped, in either direction.
 */
@Service
public class ContradictionFinder {

    private static final Logger log = LoggerFactory.getLogger(ContradictionFinder.class);

    private final ChatClient synthesisClient;
    private final ExtractionRepository extractions;
    private final ContradictionRepository contradictions;
    private final ApiCallLogRepository apiLog;
    private final LitreviewProperties.Synthesis config;
    private final String modelName;

    public ContradictionFinder(@Qualifier("synthesisClient") ChatClient synthesisClient,
            ExtractionRepository extractions, ContradictionRepository contradictions,
            ApiCallLogRepository apiLog, LitreviewProperties props,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String modelName) {
        this.synthesisClient = synthesisClient;
        this.extractions = extractions;
        this.contradictions = contradictions;
        this.apiLog = apiLog;
        this.config = props.synthesis();
        this.modelName = modelName;
    }

    /**
     * Scans every construct group and records any new contradictions found.
     *
     * @return rows written by this run, excluding pairs already on record
     */
    public List<ContradictionEntity> find() {
        List<String> constructs = extractions.findAllDistinctConstructs();
        log.info("Contradiction scan: {} construct(s)", constructs.size());

        Set<String> known = existingPairs();
        List<ContradictionEntity> written = new ArrayList<>();

        for (String construct : constructs) {
            List<ExtractionEntity> group = extractions.findByAnyConstruct(new String[] { construct });
            if (group.size() < config.minPapersPerConstruct()) {
                continue;
            }
            try {
                written.addAll(scanConstruct(construct, group, known));
            }
            catch (RuntimeException e) {
                // One unparseable model response must not abort the whole scan.
                log.warn("Contradiction scan failed for construct '{}': {}", construct, e.getMessage());
            }
        }

        log.info("Contradiction scan: {} new finding(s)", written.size());
        return written;
    }

    private List<ContradictionEntity> scanConstruct(String construct, List<ExtractionEntity> group,
            Set<String> known) {

        // Only papers that actually state a finding can take a side.
        List<ExtractionEntity> withFindings = group.stream()
                .filter(e -> e.getKeyFinding() != null && !e.getKeyFinding().isBlank())
                .toList();
        if (withFindings.size() < config.minPapersPerConstruct()) {
            return List.of();
        }

        Map<String, Paper> byCitekey = withFindings.stream()
                .map(ExtractionEntity::getPaper)
                .collect(Collectors.toMap(Paper::getCitekey, Function.identity(), (a, b) -> a));

        Findings findings;
        try {
            findings = synthesisClient.prompt()
                    .user(contradictionPrompt(construct, withFindings))
                    .call()
                    .entity(Findings.class);
        }
        catch (RuntimeException e) {
            logBestEffort(ApiCallLogEntity.failure(null, "contradiction", modelName,
                    "construct '" + construct + "': " + e.getMessage()));
            throw e;
        }

        if (findings == null || findings.contradictions() == null || findings.contradictions().isEmpty()) {
            logBestEffort(ApiCallLogEntity.success(null, "contradiction", modelName, null, null));
            log.debug("No contradiction found for construct '{}'", construct);
            return List.of();
        }

        logBestEffort(ApiCallLogEntity.success(null, "contradiction", modelName, null, null));

        List<ContradictionEntity> out = new ArrayList<>();
        for (Finding finding : findings.contradictions()) {
            Paper paperA = byCitekey.get(finding.paperACitekey());
            Paper paperB = byCitekey.get(finding.paperBCitekey());
            if (paperA == null || paperB == null) {
                // The model named a citekey that was not in its input; that is a
                // fabrication, and storing it would put a claim in the database
                // that no paper supports.
                log.warn("Dropping contradiction on '{}': unknown citekey {} / {}",
                        construct, finding.paperACitekey(), finding.paperBCitekey());
                continue;
            }
            String key = pairKey(construct, paperA.getCitekey(), paperB.getCitekey());
            if (!known.add(key)) {
                log.debug("Contradiction on '{}' between {} and {} already on record - keeping existing",
                        construct, paperA.getCitekey(), paperB.getCitekey());
                continue;
            }
            out.add(contradictions.save(new ContradictionEntity(
                    construct, paperA, finding.claimA(), paperB, finding.claimB(),
                    finding.possibleModerator())));
        }
        return out;
    }

    // ------------------------------------------------------------------ prompts

    private String contradictionPrompt(String construct, List<ExtractionEntity> group) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                The papers below all study the construct "%s".

                Identify pairs of papers whose findings genuinely conflict about this
                construct - one claims something the other contradicts.

                Rules:
                - Only report a real conflict. Differences in context, method or scope are
                  NOT contradictions; two papers can both be right.
                - Use only the citekeys given below. Do not invent citekeys.
                - Return an empty list if nothing genuinely conflicts. That is a valid and
                  expected answer.
                - If a condition could reconcile the two claims (sector, country, time
                  period, measurement), record it as possibleModerator. Null if none.

                """.formatted(construct));

        for (ExtractionEntity e : group) {
            sb.append("- ").append(e.getPaper().getCitekey()).append(": ")
                    .append(e.getKeyFinding()).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------- state

    /**
     * Keys of contradictions already recorded, as
     * {@code construct|citekeyA|citekeyB} with citekeys sorted.
     *
     * <p>Sorting makes the key direction-independent: the model may report the
     * same conflict as (A,B) on one run and (B,A) on the next, and both must map
     * to the same entry.
     */
    private Set<String> existingPairs() {
        Set<String> keys = new HashSet<>();
        for (ContradictionEntity c : contradictions.findAll()) {
            keys.add(pairKey(c.getConstruct(), c.getPaperA().getCitekey(), c.getPaperB().getCitekey()));
        }
        return keys;
    }

    private static String pairKey(String construct, String citekeyA, String citekeyB) {
        String a = citekeyA.compareTo(citekeyB) <= 0 ? citekeyA : citekeyB;
        String b = citekeyA.compareTo(citekeyB) <= 0 ? citekeyB : citekeyA;
        return construct.toLowerCase() + "|" + a + "|" + b;
    }

    // ------------------------------------------------------------------- reads

    public List<ContradictionEntity> findAll() {
        return contradictions.findAll();
    }

    public List<ContradictionEntity> findUnreviewed() {
        return contradictions.findByReviewedFalse();
    }

    /**
     * Records a human's judgement on a finding.
     *
     * <p>The only mutation this service performs on an existing row. Everything
     * else the finder does is additive precisely so this survives a re-run.
     */
    public ContradictionEntity review(Long id, String verdict) {
        ContradictionEntity entity = contradictions.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No contradiction with id " + id));
        entity.review(verdict);
        return contradictions.save(entity);
    }

    public Optional<ContradictionEntity> findById(Long id) {
        return contradictions.findById(id);
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
     * Structured-output wrapper.
     *
     * <p>A record holding the list rather than a bare {@code List<Finding>}:
     * a top-level JSON array gives the model no field name to anchor on and no
     * place to put an explicit "nothing found", which is the answer we most
     * need it to be able to give.
     */
    @JsonClassDescription("Contradictions found across a group of papers.")
    public record Findings(
            @JsonPropertyDescription("Genuine contradictions, or an empty list if none.")
            List<Finding> contradictions) {
    }

    /** One conflicting pair, as the model reports it. */
    @JsonClassDescription("A genuine conflict between two papers on one construct.")
    public record Finding(
            @JsonPropertyDescription("Citekey of the first paper, exactly as given in the input.")
            String paperACitekey,

            @JsonPropertyDescription("What the first paper claims about the construct.")
            String claimA,

            @JsonPropertyDescription("Citekey of the second paper, exactly as given in the input.")
            String paperBCitekey,

            @JsonPropertyDescription("What the second paper claims, which conflicts with claimA.")
            String claimB,

            @JsonPropertyDescription("A condition that could reconcile the two claims, or null.")
            String possibleModerator) {
    }
}
