package se.ltu.d7065e.diagnosis.control;

import java.util.List;
import se.ltu.d7065e.diagnosis.entity.Evidence;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;

/**
 * What the classifier decided and why — both sides of it. R-5 asks for every diagnosis to be
 * reconstructable, which means the evidence that argued <em>against</em> the chosen class, and
 * for the classes not chosen, has to survive too: "all six rooms are elevated" and "only this
 * room is elevated" are the same decision tree reporting opposite things, and a diagnosis that
 * keeps only the winning line cannot be argued with.
 */
public record Diagnosis(FaultClassification classification, List<Evidence> evidence) {

    public Diagnosis {
        if (classification == null) {
            throw new IllegalArgumentException("a diagnosis needs a classification");
        }
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
    }

    public List<String> explanation() {
        return evidence.stream()
                .map(e -> (e.supportsFault() ? "+ " : "- ") + e.observation())
                .toList();
    }
}
