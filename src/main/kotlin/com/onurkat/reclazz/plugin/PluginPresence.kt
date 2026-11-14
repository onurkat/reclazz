/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.cl.PluginAwareClassLoader

/** Metadata of this plugin, obtained from its own loader rather than a plugin registry. */
internal object PluginPresence {
    fun enabledDescriptor(id: String): IdeaPluginDescriptor? {
        val loader = PluginPresence::class.java.classLoader as? PluginAwareClassLoader ?: return null
        val descriptor = loader.pluginDescriptor as? IdeaPluginDescriptor ?: return null
        return descriptor.takeIf { it.pluginId.idString == id && it.isEnabled }
    }
}
