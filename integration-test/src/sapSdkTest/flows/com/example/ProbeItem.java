/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example;

import de.hybris.platform.core.model.ItemModel;
import de.hybris.platform.servicelayer.model.ItemModelContext;

/** Synthetic generated-model shape; no item type or stored item is installed. */
public class ProbeItem extends ItemModel {
    public final String nonce;
    public String stored;
    public ProbeItem(ItemModelContext context, String nonce) { super(context); this.nonce = nonce; }
    public String getComputed() { return getPersistenceContext().getDynamicValue(this, "computed"); }
    public void setComputed(String value) { getPersistenceContext().setDynamicValue(this, "computed", value); }
}
