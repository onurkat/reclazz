package com.onurkat.reclazztest.batch;

import de.hybris.platform.core.model.ItemModel;
import de.hybris.platform.servicelayer.model.attribute.DynamicAttributeHandler;
import java.util.concurrent.ConcurrentHashMap;

/** Native dynamic callbacks; setter evidence is deliberately nonpersistent. */
public class DynamicLabelHandler implements DynamicAttributeHandler<String, ItemModel> {
    private static final ConcurrentHashMap<String, String> WRITES = new ConcurrentHashMap<>();
    @Override public String get(ItemModel model) {
        return "dyn-v1:" + model.<String>getProperty("nonce");
    }
    @Override public void set(ItemModel model, String value) {
        WRITES.put(model.getPk().toString(), "set-v1:" + value);
    }
    public static String observed(String pk) { return WRITES.get(pk); }
    public static void clear(String pk) { WRITES.remove(pk); }
    public static int entries() { return WRITES.size(); }
}
