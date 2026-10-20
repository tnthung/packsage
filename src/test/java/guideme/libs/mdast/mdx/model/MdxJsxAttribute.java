package guideme.libs.mdast.mdx.model;

import guideme.libs.mdast.model.MdAstNode;

public class MdxJsxAttribute extends MdAstNode implements MdxJsxAttributeNode {
    public String name;
    private final String stringValue;
    private final String expressionValue;

    private MdxJsxAttribute(String name, String stringValue, String expressionValue) {
        this.name = name;
        this.stringValue = stringValue;
        this.expressionValue = expressionValue;
    }

    public static MdxJsxAttribute string(String name, String value) { return new MdxJsxAttribute(name, value, null); }
    public static MdxJsxAttribute expression(String name, String value) { return new MdxJsxAttribute(name, null, value); }
    public boolean hasStringValue() { return stringValue != null; }
    public String getStringValue() { return stringValue; }
    public boolean hasExpressionValue() { return expressionValue != null; }
    public String getExpressionValue() { return expressionValue; }
}
