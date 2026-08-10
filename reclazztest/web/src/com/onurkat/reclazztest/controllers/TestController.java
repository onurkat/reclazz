package com.onurkat.reclazztest.controllers;

import com.onurkat.reclazztest.services.CacheTestService;
import com.onurkat.reclazztest.services.EventTestService;
import com.onurkat.reclazztest.services.HelperService;
import com.onurkat.reclazztest.services.SchedulerTestService;
import com.onurkat.reclazztest.services.TestDao;
import com.onurkat.reclazztest.services.TestService;
import com.onurkat.reclazztest.interceptors.TestValidateInterceptor;

import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.search.SearchResult;
import de.hybris.platform.core.model.user.TitleModel;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/test")
public class TestController {

    @Autowired
    private TestService testService;

    @Autowired
    private HelperService helperService;

    @Autowired
    private CacheTestService cacheTestService;

    @Autowired
    private SchedulerTestService schedulerTestService;

    @Autowired
    private EventTestService eventTestService;

    @Autowired
    private TestDao testDao;

    @Autowired
    private FlexibleSearchService flexibleSearchService;

    @RequestMapping(value = "/ping", method = RequestMethod.GET)
    public String ping() {
        return testService.ping();
    }

    @RequestMapping(value = "/greeting", method = RequestMethod.GET)
    public String greeting() {
        return testService.getGreeting();
    }

    @RequestMapping(value = "/echo", method = RequestMethod.GET)
    public String echo(@RequestParam String msg,
                       @RequestParam(required = false, defaultValue = "") String suffix) {
        return testService.echo(msg, suffix);
    }

    @RequestMapping(value = "/removable", method = RequestMethod.GET)
    public String removable() {
        return testService.removable();
    }

    @RequestMapping(value = "/service", method = RequestMethod.GET)
    public String service() {
        return testService.getServiceVersion();
    }

    @RequestMapping(value = "/multi", method = RequestMethod.GET)
    public String multi() {
        return helperService.getHelperVersion() + ":" + testService.getServiceVersion();
    }

    @RequestMapping(value = "/cache", method = RequestMethod.GET)
    public String cache(@RequestParam String key) {
        return cacheTestService.getCachedValue(key);
    }

    @RequestMapping(value = "/scheduler", method = RequestMethod.GET)
    public String scheduler() {
        return schedulerTestService.getLastRun();
    }

    @RequestMapping(value = "/event", method = RequestMethod.GET)
    public String event() {
        eventTestService.triggerEvent();
        return eventTestService.getLastEvent();
    }

    @RequestMapping(value = "/interceptor", method = RequestMethod.GET)
    public String interceptor() {
        return TestValidateInterceptor.getLastValidation();
    }

    @RequestMapping(value = "/impex-title", method = RequestMethod.GET)
    public String impexTitle() {
        final String query = "SELECT {pk} FROM {Title} WHERE {code} = ?code";
        final java.util.Map<String, Object> params = java.util.Collections.singletonMap("code", "reclazzTestTitle");
        final SearchResult<TitleModel> result = flexibleSearchService.search(query, params);
        if (result.getResult().isEmpty()) {
            return "not-found";
        }
        return result.getResult().get(0).getName();
    }

    @RequestMapping(value = "/dao", method = RequestMethod.GET)
    public String dao() {
        return testDao.query();
    }
}
