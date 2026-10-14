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

    /**
     * Reads one current text fragment, charging extraction before copying and respecting maxChars.
     * Continued ranges within one field overlap up to FRAGMENT_OVERLAP_CHARS characters. A new field starts without overlap.
     */
    Fragment readFragment(String id, String fragmentPosition, int maxChars, Toolbox.DocumentBudget budget);

    /** Resolves a current candidate and its first bounded fragment without using a previous body. */
    ResolvedDocument resolve(String id, int maxChars, Toolbox.DocumentBudget budget);

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

    /** nextPosition marks the last source entry traversed, including hidden entries. */
    record CandidatePage(List<Candidate> candidates, String nextPosition, boolean more,
                         Evidence.Coverage coverage, String detail) {
        public CandidatePage {
            candidates = List.copyOf(candidates);
            Objects.requireNonNull(coverage);
            if (more && nextPosition == null) throw new IllegalArgumentException("more candidates require a continuation position");
        }
    }

    record Fragment(String text, String sourcePosition, int overlapChars, String nextPosition,
                    Evidence.Coverage coverage, String detail) {
        public Fragment {
            Objects.requireNonNull(text);
            Objects.requireNonNull(sourcePosition);
            Objects.requireNonNull(coverage);
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
