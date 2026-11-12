package com.onurkat.reclazztest.controllers;

import com.onurkat.reclazztest.batch.ProbeImpexState;
import de.hybris.platform.core.PK;
import de.hybris.platform.core.Registry;
import de.hybris.platform.core.model.user.TitleModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.tx.Transaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Only setup/read/cleanup of two nonce-owned Titles; never imports or updates a test phase. */
@RestController
@RequestMapping("/test/batch/impex-update")
public class ImpexUpdateProbeController {
    @Autowired private ModelService modelService;
    @Autowired private FlexibleSearchService searchService;
    private String current;
    private final Map<String,PK> pks=new LinkedHashMap<>();
    private void owned(String run) {
        ProbeImpexState.validate(run);
        if(!"master".equals(Registry.getCurrentTenant().getTenantID()))throw new IllegalStateException("Master tenant required");
    }
    private List<TitleModel> rows(String run) {
        owned(run);
        var q=new FlexibleSearchQuery("SELECT {pk} FROM {Title} WHERE {code} LIKE ?prefix ORDER BY {code}");
        q.addQueryParameter("prefix",run+"-%");q.setDisableSearchRestrictions(true);
        List<TitleModel> found=searchService.<TitleModel>search(q).getResult();
        for(TitleModel row:found)if(!List.of("target","control").contains(row.getCode().substring(run.length()+1)))throw new IllegalStateException("Unexpected owned-prefix row");
        return found;
    }
    @GetMapping("/gates")
    public Map<String,Boolean> gates() throws ReflectiveOperationException {
        Class<?> agent=Class.forName("com.onurkat.reclazz.agent.ReclazzAgent",false,ClassLoader.getSystemClassLoader());
        Object config=agent.getMethod("getConfig").invoke(null);
        return Map.of("autoImpex",(Boolean)config.getClass().getMethod("isAutoImpex").invoke(config),
                "impexAllowRemove",(Boolean)config.getClass().getMethod("isImpexAllowRemove").invoke(config));
    }
    @PostMapping("/begin")
    public synchronized Map<String,Object> begin(@RequestParam("run") String run) {
        owned(run);if(current!=null||!rows(run).isEmpty())throw new IllegalStateException("Dirty baseline");
        Transaction tx=Transaction.current();if(tx.isRunning())throw new IllegalStateException("Probe needs its own transaction");
        Map<String,PK> created=new LinkedHashMap<>();tx.begin();
        try {
            for(String role:List.of("target","control")) {
                TitleModel row=modelService.create(TitleModel.class);row.setCode(run+"-"+role);
                row.setName(run+(role.equals("target")?":v0":":control"),Locale.ENGLISH);
                row.setName(run+":ordinary",Locale.GERMAN);modelService.save(row);created.put(role,row.getPk());
            }
            tx.commit();
        } finally {if(tx.isRunning())tx.rollback();modelService.detachAll();}
        current=run;pks.putAll(created);return read(run);
    }
    @GetMapping("/read")
    public synchronized Map<String,Object> read(@RequestParam("run") String run) {
        owned(run);if(!run.equals(current))throw new IllegalStateException("Wrong active run");
        modelService.detachAll();var found=rows(run);Map<String,Object> result=new LinkedHashMap<>();
        for(TitleModel row:found) {
            String role=row.getCode().substring(run.length()+1);
            if(!row.getPk().equals(pks.get(role)))throw new IllegalStateException("Owned PK changed");
            result.put(role,Map.of("code",row.getCode(),"pk",row.getPk().toString(),"english",row.getName(Locale.ENGLISH),"german",row.getName(Locale.GERMAN)));
        }
        return Map.of("run",run,"owned",found.size(),"rows",result);
    }
    @PostMapping("/cleanup")
    public synchronized Map<String,Object> cleanup(@RequestParam("run") String run) {
        owned(run);if(current!=null&&!run.equals(current))throw new IllegalStateException("Foreign active run");
        modelService.detachAll();var found=rows(run);modelService.removeAll(found);modelService.detachAll();
        int remaining=rows(run).size();if(remaining!=0)throw new IllegalStateException("Owned rows remain");
        current=null;pks.clear();return Map.of("run",run,"ownedRemaining",remaining);
    }
}
