package com.example.demo;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {
    private final GreetingService service;
    private final DemoProperties properties;
    private final ApplicationContext context;

    public HelloController(GreetingService service, DemoProperties properties, ApplicationContext context) {
        this.service = service;
        this.properties = properties;
        this.context = context;
    }

    @GetMapping("/hello")
    public String hello() { return "controller-before:" + service.message(); }

    @GetMapping("/message")
    public Greeting message() { return new Greeting("boot4"); }

    @GetMapping("/configuration")
    public String configuration() { return properties.getLabel(); }

    @GetMapping("/beans/added")
    public String addedBean() {
        return context.containsBean("addedService") ? context.getBean("addedService").toString() : "missing";
    }

    @GetMapping("/runtime")
    public Map<String, Object> runtime() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", ProcessHandle.current().pid());
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type != null && (type.getName().startsWith("tools.jackson.") || type.getName().contains("Jackson"))) {
                result.put(name, type.getName());
            }
        }
        return result;
    }
}
