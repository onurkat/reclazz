/*
 * Copyright 2026 Onur Kat
 * SPDX-License-Identifier: Apache-2.0
 */
package com.onurkat.reclazz.plugin.ui

/**
 * Counting things out loud, on the plugin side.
 *
 * The status bar is the one Reclazz surface that is on screen all day, and
 * after the first reload of a session it read "Reclazz: 1 reloads". The agent
 * has the same helper for the same reason; this is the copy the IDE module
 * gets, since the two do not share code.
 */
internal fun plural(count: Int, singular: String, plural: String = singular + "s"): String =
    "$count " + if (count == 1) singular else plural
