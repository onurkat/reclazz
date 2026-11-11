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

/** Only fixed header literals and this run's exact Title rows are accepted. */
@RestController
@RequestMapping("/test/batch/impex-translator")
public class ImpexTranslatorProbeController {
    @Autowired private ModelService modelService;
    @Autowired private FlexibleSearchService searchService;
    private String current;private long baseline;
    private final Map<String,PK> pks=new LinkedHashMap<>();
    private String owned(String run) {
        ProbeImpexState.validate(run);
        if(!"master".equals(Registry.getCurrentTenant().getTenantID()))throw new IllegalStateException("Master tenant required");
        return run;
    }
    private List<TitleModel> rows(String run) {
        var q=new FlexibleSearchQuery("SELECT {pk} FROM {Title} WHERE {code} LIKE ?prefix ORDER BY {code}");
        q.addQueryParameter("prefix",owned(run)+"-%");q.setDisableSearchRestrictions(true);
        List<TitleModel> rows=searchService.<TitleModel>search(q).getResult();
        for(TitleModel row:rows)if(!ProbeImpexState.ROLES.stream().anyMatch(role->row.getCode().equals(run+"-"+role)))throw new IllegalStateException("Foreign owned-prefix row");
        return rows;
    }
    private long total() {
        var q=new FlexibleSearchQuery("SELECT COUNT({pk}) FROM {Title}");q.setDisableSearchRestrictions(true);q.setResultClassList(List.of(Long.class));
        return ((Number)searchService.search(q).getResult().get(0)).longValue();
    }
    private void require(String run) {owned(run);if(!run.equals(current))throw new IllegalStateException("Wrong active run");}
    @PostMapping("/begin")
    public synchronized Map<String,Object> begin(@RequestParam("run") String run) throws Exception {
        owned(run);if(current!=null||!ProbeImpexState.idle()||!rows(run).isEmpty())throw new IllegalStateException("Dirty probe baseline");
        var languages=new FlexibleSearchQuery("SELECT {isocode} FROM {Language} WHERE {isocode} IN (?codes)");
        languages.addQueryParameter("codes",List.of("en","de"));languages.setResultClassList(List.of(String.class));
        if(!new HashSet<>(searchService.<String>search(languages).getResult()).equals(Set.of("en","de")))throw new IllegalStateException("Existing en/de languages required");
        baseline=total();current=run;
        Transaction tx=Transaction.current();if(tx.isRunning())throw new IllegalStateException("Probe needs its own transaction");
        tx.begin();
        try {
            for(String role:ProbeImpexState.ROLES) {
                TitleModel row=modelService.create(TitleModel.class);row.setCode(run+"-"+role);
                String english=role.equals("control")?run+":control":role.substring(0,role.indexOf('-'))+"-v1:"+ProbeImpexState.token(run);
                row.setName(english,Locale.ENGLISH);row.setName(ProbeImpexState.german(run),Locale.GERMAN);modelService.save(row);pks.put(role,row.getPk());
            }
            tx.commit();
        }finally {if(tx.isRunning())tx.rollback();modelService.detachAll();}
        ProbeImpexState.begin(run,pks);return read(run);
    }
    @GetMapping("/script")
    public String script(@RequestParam("run") String run,@RequestParam("kind") String kind) {
        require(run);return ProbeImpexState.script(run,kind,kind+"-fresh",1);
    }
    @PostMapping("/advance")
    public Map<String,Object> advance(@RequestParam("run") String run)throws Exception {
        require(run);return ProbeImpexState.advance(run);
    }
    @GetMapping("/read")
    public Map<String,Object> read(@RequestParam("run") String run) {
        require(run);modelService.detachAll();List<TitleModel> found=rows(run);
        Map<String,Object> persisted=new LinkedHashMap<>();
        for(TitleModel row:found) {
            String role=row.getCode().substring(run.length()+1);
            if(!row.getPk().equals(pks.get(role)))throw new IllegalStateException("Persisted PK changed");
            persisted.put(role,Map.of("pk",row.getPk().toString(),"code",row.getCode(),"english",row.getName(Locale.ENGLISH),"ordinary",row.getName(Locale.GERMAN)));
        }
        return Map.of("run",run,"rows",persisted,"owned",found.size(),"global",total(),"baseline",baseline,"native",ProbeImpexState.snapshot(run));
    }
    @PostMapping("/cleanup")
    public synchronized Map<String,Object> cleanup(@RequestParam("run") String run)throws Exception {
        owned(run);if(current!=null&&!run.equals(current))throw new IllegalStateException("Foreign active probe");
        if(current==null&&ProbeImpexState.idle())baseline=total();
        Exception failure=null;
        if(!ProbeImpexState.idle()) {
            try{ProbeImpexState.close(run);}catch(Exception e){failure=e;}
            // forget refuses a live worker; rows must not be removed while import is active.
            ProbeImpexState.forget(run);
        }
        modelService.detachAll();List<TitleModel> found=rows(run);int count=found.size();modelService.removeAll(found);modelService.detachAll();
        int remaining=rows(run).size();long global=total();long expected=baseline;
        current=null;pks.clear();
        if(remaining!=0||global!=expected)throw new IllegalStateException("Title baseline not restored");
        if(failure!=null)throw failure;
        return Map.of("run",run,"removed",count,"owned",remaining,"idle",ProbeImpexState.idle(),"global",global,"baseline",expected);
    }
}
