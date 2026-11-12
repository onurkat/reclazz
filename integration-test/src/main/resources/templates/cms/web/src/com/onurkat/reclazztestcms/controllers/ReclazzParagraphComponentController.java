package com.onurkat.reclazztestcms.controllers;

import de.hybris.platform.acceleratorstorefrontcommons.controllers.cms.AbstractCMSComponentController;
import de.hybris.platform.cms2.model.contents.components.CMSParagraphComponentModel;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

/** Uses the real inherited handler; only the owned fillModel body is edited. */
@Controller
@RequestMapping("/test/batch/cms/paragraph")
public class ReclazzParagraphComponentController extends AbstractCMSComponentController<CMSParagraphComponentModel> {
    @Override
    protected void fillModel(HttpServletRequest request, Model model, CMSParagraphComponentModel component) {
        model.addAttribute("nonce", request.getAttribute("reclazzCmsNonce"));
        model.addAttribute("marker", "cms-v1");
        if (component.getPk()!=null) {
            String content=component.getContent(Locale.ENGLISH);
            model.addAttribute("marker", "selected-target".equals(content) ? "uid-v1" : "uid-stable");
            if(content.contains("-page-")) model.addAttribute("marker","selected-page-target".equals(content) ? "page-v1" : "page-stable");
        }
        model.addAttribute("content", component.getContent(Locale.ENGLISH));
        model.addAttribute("unsaved", component.getPk() == null);
    }

    @Override
    protected String getView(CMSParagraphComponentModel component) {
        if(component.getPk()!=null && component.getContent(Locale.ENGLISH).contains("-page-"))return "reclazz-page-slot";
        return component.getPk()==null ? "reclazz-paragraph" : "reclazz-uid-paragraph";
    }
}
