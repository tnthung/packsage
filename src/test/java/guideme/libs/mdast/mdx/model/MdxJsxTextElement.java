package guideme.libs.mdast.mdx.model;

import guideme.libs.mdast.model.MdAstParent;

import java.util.List;

public final class MdxJsxTextElement extends MdAstParent implements MdxJsxElementFields {
    private final String name;
    private final List<MdxJsxAttributeNode> attributes;

    public MdxJsxTextElement(String name, List<MdxJsxAttributeNode> attributes) {
        this.name = name;
        this.attributes = List.copyOf(attributes);
    }

    @Override public String name() { return name; }
    @Override public List<MdxJsxAttributeNode> attributes() { return attributes; }
}
