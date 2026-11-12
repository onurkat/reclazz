package com.onurkat.reclazztest.controllers;

import java.lang.management.ManagementFactory;
import java.util.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Read-only UUID-owned enrollment facts; never registers or removes a bean/handler. */
@RestController
@RequestMapping("/test/batch/new-occ")
public class NewOccControllerProbeController {
    @GetMapping("/facts")
    public Map<String,Object> facts(@RequestParam("nonce") String nonce) throws ReflectiveOperationException {
        if(!nonce.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("Invalid owned nonce");
        String className="com.onurkat.reclazztest.occ.ReclazzNewOcc"+nonce;
        String beanName="reclazzC22"+nonce;
        Class<?> holder=Class.forName("com.onurkat.reclazz.platform.ApplicationContextHolder",false,ClassLoader.getSystemClassLoader());
        List<?> contexts=(List<?>)holder.getMethod("getAllContexts").invoke(null);
        var rows=new ArrayList<Map<String,Object>>();int beanCount=0,handlerCount=0,index=0;
        for(Object value:contexts) {
            var context=(ConfigurableApplicationContext)value;var factory=context.getBeanFactory();
            var row=new LinkedHashMap<String,Object>();row.put("index",index++);row.put("context",context.getClass().getName());
            boolean local=factory.containsBeanDefinition(beanName)||factory.containsSingleton(beanName);
            row.put("localBean",local);
            if(local) {
                Class<?> type=factory.getType(beanName,false);
                if(type==null||!type.getName().equals(className))throw new IllegalStateException("Foreign owned bean name");
                beanCount++;row.put("class",type.getName());row.put("definingLoaderMatches",type.getClassLoader()==factory.getBeanClassLoader());
            }
            var handlers=new ArrayList<Map<String,Object>>();
            for(String name:factory.getBeanNamesForType(RequestMappingHandlerMapping.class,true,false)) {
                var mapping=factory.getBean(name,RequestMappingHandlerMapping.class);
                for(var entry:mapping.getHandlerMethods().entrySet()) {
                    var handler=entry.getValue();
                    if(!handler.getBeanType().getName().equals(className))continue;
                    if(!entry.getKey().toString().contains("/reclazz-c22-"+nonce))throw new IllegalStateException("Unexpected owned handler path");
                    handlers.add(Map.of("mappingClass",mapping.getClass().getName(),"bean",String.valueOf(handler.getBean()),"handlerClass",handler.getBeanType().getName(),"path",entry.getKey().toString()));handlerCount++;
                }
            }
            row.put("handlers",handlers);rows.add(row);
        }
        return Map.of("nonce",nonce,"jvmStart",ManagementFactory.getRuntimeMXBean().getStartTime(),"beanCount",beanCount,"handlerCount",handlerCount,"contexts",rows);
    }
}
