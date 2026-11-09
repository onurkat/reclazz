package com.onurkat.reclazztest.occ;

import com.onurkat.reclazztest.data.ReclazzProbeData;
import com.onurkat.reclazztest.batch.OrikaRollbackData;
import com.onurkat.reclazztest.batch.OrikaRollbackWsDTO;
import com.onurkat.reclazztest.dto.ReclazzProbeWsDTO;
import de.hybris.platform.commerceservices.request.mapping.annotation.ApiVersion;
import de.hybris.platform.webservicescommons.mapping.DataMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** Synthetic OCC endpoint: a body to edit and a WsDTO filtered by its field set. */
@Controller
@ApiVersion("v2")
@RequestMapping(value = "/{baseSiteId}/reclazzprobe")
public class ReclazzProbeOccController {

    @Resource(name = "dataMapper")
    private DataMapper dataMapper;

    @GetMapping("/orika-rollback")
    @ResponseBody
    public OrikaRollbackWsDTO rollback(@RequestParam(defaultValue = "code") final String fields) {
        return dataMapper.map(new OrikaRollbackData(), OrikaRollbackWsDTO.class, fields);
    }

    @GetMapping
    @ResponseBody
    public ReclazzProbeWsDTO probe(@RequestParam(defaultValue = "DEFAULT") final String fields) {
        final ReclazzProbeData data = new ReclazzProbeData();
        data.setCode("v0");
        data.setLabel("label0");
        return dataMapper.map(data, ReclazzProbeWsDTO.class, fields);
    }
}
