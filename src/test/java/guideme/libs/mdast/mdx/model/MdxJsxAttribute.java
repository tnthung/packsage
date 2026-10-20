package guideme.libs.mdast.mdx.model;

public final class MdxJsxAttribute implements MdxJsxAttributeNode {
    private final String name;
    private final String stringValue;
    private final Object expressionValue;

    private MdxJsxAttribute(String name, String stringValue, Object expressionValue) {
        this.name = name;
        this.stringValue = stringValue;
        this.expressionValue = expressionValue;
    }

    public static MdxJsxAttribute string(String name, String value) { return new MdxJsxAttribute(name, value, null); }
    public static MdxJsxAttribute expression(String name, Object value) { return new MdxJsxAttribute(name, null, value); }
    public String name() { return name; }
    public boolean hasStringValue() { return stringValue != null; }
    public String getStringValue() { return stringValue; }
    public boolean hasExpressionValue() { return expressionValue != null; }
    public Object getExpressionValue() { return expressionValue; }
}
