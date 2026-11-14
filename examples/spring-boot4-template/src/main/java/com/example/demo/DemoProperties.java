package com.example.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("demo")
public class DemoProperties {
    private String label;
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}
