package guideme.libs.mdast.mdx.model;

import guideme.libs.mdast.model.MdAstNode;

import java.util.List;

public interface MdxJsxElementFields {
    String name();
    List<MdxJsxAttributeNode> attributes();
    List<? extends MdAstNode> children();
}
