package com.onurkat.reclazztest.controllers;

import com.onurkat.reclazztest.batch.ProbeValidationState;
import de.hybris.platform.core.Registry;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.type.TypeService;
import de.hybris.platform.validation.services.ValidationService;
import de.hybris.platform.validation.daos.ConstraintDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/test/batch/validation-constraints")
public class ValidationConstraintsProbeController {
    @Autowired private ModelService models;
    @Autowired private FlexibleSearchService search;
    @Autowired private TypeService types;
    @Autowired private ValidationService validation;
    @Autowired private ConstraintDao dao;
    private String owned(String run){ProbeValidationState.owned(run);if(!"master".equals(Registry.getCurrentTenant().getTenantID()))throw new IllegalStateException("Master tenant required");return run;}
    @PostMapping("/begin") public synchronized Map<String,Object> begin(@RequestParam("run")String run,@RequestParam(value="mode",defaultValue="normal")String mode)throws Exception {
        owned(run);if(!List.of("normal","partial").contains(mode))throw new IllegalArgumentException();ProbeValidationState.open(run,models,search,types,validation,dao);return ProbeValidationState.call(run,mode.equals("partial")?"partial":"begin");
    }
    @GetMapping("/read") public Map<String,Object> read(@RequestParam("run")String run)throws Exception{return ProbeValidationState.call(owned(run),"read");}
    @GetMapping("/script") public String script(@RequestParam("run")String run,@RequestParam("version")String version)throws Exception {
        owned(run);if(!List.of("v1","v2","unrelated-v1","unrelated-v2").contains(version))throw new IllegalArgumentException();return (String)ProbeValidationState.call(run,"script-"+version).get("script");
    }
    @PostMapping("/diagnostic") public Map<String,Object> diagnostic(@RequestParam("run")String run,@RequestParam("action")String action)throws Exception {
        if(!List.of("watch-arm","watch-observation","constraint-fingerprint","import-inherited","import-watcher","import-native").contains(action))throw new IllegalArgumentException();return ProbeValidationState.call(owned(run),action);
    }
    @PostMapping("/witness") public Map<String,Object> witness(@RequestParam("run")String run)throws Exception{return ProbeValidationState.call(owned(run),"witness");}
    @PostMapping("/cleanup") public Map<String,Object> cleanup(@RequestParam("run")String run)throws Exception {
        owned(run);if(ProbeValidationState.idle()) {
            int count=0;
            for(String type:List.of("AbstractConstraint","ConstraintGroup")) {
                var query=new de.hybris.platform.servicelayer.search.FlexibleSearchQuery("SELECT {pk} FROM {"+type+"} WHERE {id} LIKE ?prefix");query.addQueryParameter("prefix",run+"-%");query.setDisableSearchRestrictions(true);count+=search.search(query).getResult().size();
            }
            var query=new de.hybris.platform.servicelayer.search.FlexibleSearchQuery("SELECT {pk} FROM {ReclazzSchemaProbe} WHERE {nonce} IN (?nonces)");query.addQueryParameter("nonces",List.of(run+"-aaaa",run+"-bbbbbbbb",run+"-zzzz"));query.setDisableSearchRestrictions(true);count+=search.search(query).getResult().size();
            return Map.of("run",run,"owned",count,"removed",0,"idle",true,"baselineChecked",false,"heldRemaining",0,"workerAlive",false,"sessionClosed",true);
        }
        return ProbeValidationState.close(run);
    }
}
