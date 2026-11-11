package com.onurkat.reclazztest.batch;

import de.hybris.platform.util.CSVCellDecorator;
import java.util.Map;

/** Separate decorator oracle; does not delegate to the value translator. */
public class ProbeImpexCellDecorator implements CSVCellDecorator {
    public String decorate(int position, Map<Integer,String> line) {
        String value=line.get(position);
        String result="decorator-v1:" + value;
        ProbeImpexState.observed(this,"decorator",value,result);
        return result;
    }
}
