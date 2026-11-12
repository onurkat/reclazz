package com.onurkat.reclazztest.occ;

import com.onurkat.reclazztest.batch.ProbeOccDateParser;
import de.hybris.platform.commerceservices.request.mapping.annotation.ApiVersion;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.WebApplicationContext;

/** Synthetic consumer in the same OCC mapping/security scope as the existing probe. */
@Controller
@ApiVersion("v2")
@RequestMapping(value = "/{baseSiteId}/reclazzdate")
public class OccDateProbeController implements ApplicationContextAware {
    @Resource(name = "reclazzOwnedOccDateParser")
    private ProbeOccDateParser parser;
    private ApplicationContext context;

    @Override
    public void setApplicationContext(final ApplicationContext value) {
        context = value;
    }

    @GetMapping
    @ResponseBody
    public Map<String, Object> parse(@RequestParam("timestamp") final String timestamp,
            @RequestParam("token") final String token,
            final HttpServletRequest request, final HttpServletResponse response) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("contextId", context.getId());
        result.put("contextIdentity", System.identityHashCode(context));
        result.put("jvmStart", ManagementFactory.getRuntimeMXBean().getStartTime());
        result.put("webContext", context instanceof WebApplicationContext);
        result.put("sameServingContext", context instanceof WebApplicationContext web
                && web.getServletContext() == request.getServletContext());
        result.put("localBean", context.containsLocalBean("reclazzOwnedOccDateParser"));
        result.put("sameBean", context.getBean("reclazzOwnedOccDateParser") == parser);
        result.put("parserClass", parser.getClass().getName());
        result.put("converterContract", Converter.class.isAssignableFrom(parser.getClass()));
        result.put("parserLoader", parser.getClass().getClassLoader().getClass().getName());
        result.put("contextLoader", context.getClassLoader().getClass().getName());
        result.put("occPath", request.getContextPath());
        result.put("controlEpoch", Instant.parse("2026-11-12T00:34:05Z").toEpochMilli());
        result.put("locale", Locale.getDefault().toLanguageTag());
        result.put("zone", TimeZone.getDefault().getID());
        final long before = parser.getCalls();
        try {
            result.put("epoch", parser.convert(timestamp).getTime());
        } catch (DateTimeParseException invalid) {
            response.setStatus(400);
            result.put("error", "invalid-timestamp");
        }
        result.put("targetCalls", parser.getCalls() - before);
        return result;
    }
}
