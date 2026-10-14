package guideme.libs.mdast.model;

import java.util.ArrayList;
import java.util.List;

public abstract class MdAstParent extends MdAstNode {
    private final List<MdAstNode> children = new ArrayList<>();

    public List<MdAstNode> children() { return children; }

    public void add(MdAstNode node) { children.add(node); }
}
