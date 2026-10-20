package guideme.compiler;

import java.util.Map;

public final class Frontmatter {
    private final Map<String, Object> additionalProperties;

    public Frontmatter(Map<String, Object> additionalProperties) {
        this.additionalProperties = Map.copyOf(additionalProperties);
    }

    public Map<String, Object> additionalProperties() { return additionalProperties; }
}
