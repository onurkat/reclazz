package com.onurkat.reclazztest.occ;

import com.onurkat.reclazztest.batch.KeepStateProbe;
import com.onurkat.reclazztest.inherited.KeepStateInheritedService;
import de.hybris.platform.commerceservices.request.mapping.annotation.ApiVersion;
import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@ApiVersion("v2")
@RequestMapping("/{baseSiteId}/reclazzkeep")
public class KeepStateOccController {
    @Resource(name="reclazzKeepStateProbe")
    private KeepStateProbe service;
    @Resource(name="reclazzKeepStateInheritedService")
    private KeepStateInheritedService inherited;
    private int count;
    public String version() { return "controller-v1"; }
    @GetMapping("/version") @ResponseBody
    public Map<String, Object> versions() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("controllerVersion", version()); result.put("serviceVersion", service.version());
        result.put("controllerIdentity", System.identityHashCode(this));
        result.put("serviceIdentity", System.identityHashCode(service));
        result.put("inheritedIdentity", System.identityHashCode(inherited));
        result.put("inheritedVersion", inherited.version()); result.put("inheritedConfigured", inherited.configured());
        return result;
    }
    @GetMapping("/hit") @ResponseBody
    public synchronized Map<String, Object> hit() {
        Map<String, Object> result = versions();
        result.put("count", ++count); result.put("map", service.hit()); result.put("inheritedMap", inherited.hit());
        return result;
    }
}
