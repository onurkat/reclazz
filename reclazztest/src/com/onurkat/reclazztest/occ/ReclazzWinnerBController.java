package com.onurkat.reclazztest.occ;

import de.hybris.platform.commerceservices.request.mapping.annotation.ApiVersion;
import de.hybris.platform.commerceservices.request.mapping.annotation.RequestMappingOverride;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** One of two OCC handlers for the same path; the higher configured priority answers. */
@Controller
@ApiVersion("v2")
@RequestMapping(value = "/{baseSiteId}/reclazzwinner")
public class ReclazzWinnerBController {

    @GetMapping
    @ResponseBody
    @RequestMappingOverride(priorityProperty = "reclazztest.occ.winnerB.priority")
    public String winner() {
        return "B";
    }
}
