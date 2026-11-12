package com.onurkat.reclazztest.occ;

import com.onurkat.reclazztest.batch.ProbeOccErrorService;
import de.hybris.platform.commerceservices.request.mapping.annotation.ApiVersion;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.WebApplicationContext;

@Controller
@ApiVersion("v2")
@RequestMapping("/{baseSiteId}/reclazzerror")
public class OccErrorProbeController implements ApplicationContextAware {
    @Resource(name = "reclazzProbeOccErrorService")
    private ProbeOccErrorService service;
    private ApplicationContext context;

    public OccErrorProbeController() { new ReclazzOccControlException("preload"); }
    @Override public void setApplicationContext(ApplicationContext value) { context = value; }

    @GetMapping("/target")
    @ResponseBody
    public void target() { service.fail(); }

    @GetMapping("/control")
    @ResponseBody
    public void control() { throw new ReclazzOccControlException("reclazz-c67-control"); }

    @GetMapping("/success")
    @ResponseBody
    public Map<String, Object> success(HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", "reclazz-c67-stable");
        result.put("jvmStart", ManagementFactory.getRuntimeMXBean().getStartTime());
        result.put("serviceIdentity", System.identityHashCode(service));
        result.put("contextIdentity", System.identityHashCode(context));
        result.put("sameBean", context.getBean("reclazzProbeOccErrorService") == service);
        result.put("sameServingContext", context instanceof WebApplicationContext web
                && web.getServletContext() == request.getServletContext());
        result.put("localResolver", context.containsLocalBean("restHandlerExceptionResolverV2"));
        result.put("resolver", context.getBean("restHandlerExceptionResolverV2").getClass().getName());
        result.put("factory", context.getBean("webserviceErrorFactory").getClass().getName());
        result.put("converter", context.getBean("exceptionConverter").getClass().getName());
        return result;
    }

    public static class ReclazzOccControlException extends RuntimeException {
        public ReclazzOccControlException(String message) { super(message); }
    }
}
