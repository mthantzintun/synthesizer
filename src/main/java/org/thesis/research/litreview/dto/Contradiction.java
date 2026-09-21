package org.thesis.research.litreview.dto;

import org.thesis.research.litreview.entity.ContradictionEntity;

/**
 * A disagreement between two papers on the same construct, as returned by the
 * API.
 *
 * <p>Carries citekeys rather than entity ids because a contradiction is
 * something a human has to adjudicate by going back to the two papers - an
 * integer id is not something you can look up. The {@code reviewed}/{@code verdict}
 * pair is surfaced because it is the whole point of persisting these: the
 * finder produces candidates, a person decides which are real.
 *
 * @param id                {@code contradiction.id}
 * @param construct         the contested construct
 * @param paperACitekey     citekey of the first paper
 * @param paperATitle       title of the first paper, for display
 * @param claimA            what the first paper claims
 * @param paperBCitekey     citekey of the second paper
 * @param paperBTitle       title of the second paper, for display
 * @param claimB            what the second paper claims
 * @param possibleModerator a condition that could reconcile the two claims, may be null
 * @param reviewed          whether a human has adjudicated this finding
 * @param verdict           the human's note, null until reviewed
 */
public record Contradiction(
        Long id,
        String construct,
        String paperACitekey,
        String paperATitle,
        String claimA,
        String paperBCitekey,
        String paperBTitle,
        String claimB,
        String possibleModerator,
        boolean reviewed,
        String verdict) {

    public static Contradiction from(ContradictionEntity entity) {
        return new Contradiction(
                entity.getId(),
                entity.getConstruct(),
                entity.getPaperA().getCitekey(),
                entity.getPaperA().getTitle(),
                entity.getClaimA(),
                entity.getPaperB().getCitekey(),
                entity.getPaperB().getTitle(),
                entity.getClaimB(),
                entity.getPossibleModerator(),
                entity.isReviewed(),
                entity.getVerdict());
    }
}
