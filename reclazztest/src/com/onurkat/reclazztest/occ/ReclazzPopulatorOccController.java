package com.onurkat.reclazztest.occ;

import com.onurkat.reclazztest.data.ReclazzProbeData;
import com.onurkat.reclazztest.dto.ReclazzProbeWsDTO;
import de.hybris.platform.commerceservices.request.mapping.annotation.ApiVersion;
import de.hybris.platform.servicelayer.dto.converter.Converter;
import de.hybris.platform.webservicescommons.mapping.DataMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
@ApiVersion("v2")
@RequestMapping("/{baseSiteId}/reclazzpopulator")
public class ReclazzPopulatorOccController {
    @Resource(name = "dataMapper") private DataMapper dataMapper;
    @Resource(name = "reclazzProbeConverter") private Converter<String, ReclazzProbeData> converter;
    @GetMapping @ResponseBody
    public ReclazzProbeWsDTO probe() {
        return dataMapper.map(converter.convert("stable-label"), ReclazzProbeWsDTO.class, "DEFAULT");
    }
}
