package com.onurkat.reclazztest.batch;

/** Synthetic XML scalar/ref/constructor target; no platform state. */
public class XmlProbe {
    private final String constructed;
    private String value = "v1";
    private XmlProbe reference;
    public XmlProbe() { this("default"); }
    public XmlProbe(String constructed) { this.constructed = constructed; }
    public String getConstructed() { return constructed; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public XmlProbe getReference() { return reference; }
    public void setReference(XmlProbe reference) { this.reference = reference; }
}
