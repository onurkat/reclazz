package com.onurkat.reclazztest.batch;

import de.hybris.platform.impex.jalo.translators.AbstractValueTranslator;
import de.hybris.platform.jalo.Item;

/** Header-created synthetic native import consumer; body-only edits during the probe. */
public class ProbeImpexValueTranslator extends AbstractValueTranslator {
    public Object importValue(String value, Item item) {
        String result = "translator-v1:" + value;
        ProbeImpexState.observed(this,"translator",value,result);
        return result;
    }
    public String exportValue(Object value) { throw new UnsupportedOperationException("Import-only probe"); }
}
