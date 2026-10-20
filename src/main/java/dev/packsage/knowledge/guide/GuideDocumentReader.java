package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;

import java.util.List;
import java.util.Objects;

/** Live, bounded access to one installed guide source. */
public interface GuideDocumentReader {
    int MAX_QUERY_LENGTH = 512;
    int FRAGMENT_OVERLAP_CHARS = MAX_QUERY_LENGTH - 1;

    String providerId();

    String version();

    /** Identifies the current loaded source generation without retaining source objects. */
    String sourceIdentity(Evidence.Scope scope);

    /**
     * Returns at most limit candidates after afterPosition, charging source traversal before inspecting each entry.
     * A candidate sourcePosition resumes strictly after that candidate. CandidatePage.nextPosition is the bookmark after
     * the last entry inspected, including hidden entries, and is used after the whole page has been consumed.
     */
    CandidatePage candidates(String afterPosition, int limit, Toolbox.DocumentBudget budget);

    default CandidatePage candidates(String query, String afterPosition, int limit, Toolbox.DocumentBudget budget) {
        return candidates(afterPosition, limit, budget);
    }

    /**
     * Reads one current text fragment, charging extraction before copying and respecting maxChars.
     * Continued ranges within one field overlap up to FRAGMENT_OVERLAP_CHARS characters. A new field starts without overlap.
     */
    Fragment readFragment(String id, String fragmentPosition, int maxChars, Toolbox.DocumentBudget budget);

    /** Resolves a current candidate and its first bounded fragment without using a previous body. */
    ResolvedDocument resolve(String id, int maxChars, Toolbox.DocumentBudget budget);

    default List<Evidence.NavigationTarget> navigationTargets(Evidence.Scope scope, Candidate candidate) {
        return List.of();
    }

    /** Rebinds a durable destination to its current source identity, or returns null when unsupported or unavailable. */
    default Evidence.NavigationTarget refreshNavigation(Evidence.NavigationTarget target, Evidence.Scope scope) {
        return null;
    }

    /** Navigation is invoked on the client thread after the player chooses a refreshed target. */
    default Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED,
                "this guide source does not expose PackSage navigation");
    }

    record Candidate(String id, String title, String sourcePosition, List<String> provenance,
                     Evidence.Coverage coverage) {
        public Candidate {
            Objects.requireNonNull(id);
            Objects.requireNonNull(title);
            Objects.requireNonNull(sourcePosition);
            provenance = List.copyOf(provenance);
            Objects.requireNonNull(coverage);
        }
    }

    /**
     * nextPosition marks the last source entry traversed, including hidden entries. An empty page that could not charge
     * for its next source entry may return the unchanged position (including null before the first entry) with more=true.
     */
    record CandidatePage(List<Candidate> candidates, String nextPosition, boolean more,
                         Evidence.Coverage coverage, String detail) {
        public CandidatePage {
            candidates = List.copyOf(candidates);
            Objects.requireNonNull(coverage);
            if (more && nextPosition == null && !candidates.isEmpty())
                throw new IllegalArgumentException("non-empty candidate pages require a continuation position");
        }
    }

    record Fragment(String text, String sourcePosition, int overlapChars, String nextPosition,
                    Evidence.Coverage coverage, String detail, Evidence.JsonObject relationships) {
        public Fragment(String text, String sourcePosition, int overlapChars, String nextPosition,
                        Evidence.Coverage coverage, String detail) {
            this(text, sourcePosition, overlapChars, nextPosition, coverage, detail, new Evidence.JsonObject(java.util.Map.of()));
        }

        public Fragment {
            Objects.requireNonNull(text);
            Objects.requireNonNull(sourcePosition);
            Objects.requireNonNull(coverage);
            Objects.requireNonNull(relationships);
            if (overlapChars < 0 || overlapChars > text.length()) throw new IllegalArgumentException("invalid fragment overlap");
        }
    }

    record ResolvedDocument(Candidate candidate, Fragment fragment) {
        public ResolvedDocument {
            Objects.requireNonNull(candidate);
            Objects.requireNonNull(fragment);
        }
    }
}
