package guideme.libs.mdast.model;

public abstract class MdAstLiteral extends MdAstNode {
    private final String value;

    protected MdAstLiteral(String value) { this.value = value; }

    public String value() { return value; }
}
