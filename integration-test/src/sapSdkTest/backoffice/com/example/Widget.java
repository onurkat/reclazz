/* Copyright 2026 Onur Kat; SPDX-License-Identifier: Apache-2.0 */
package com.example;
import com.hybris.cockpitng.annotations.SocketEvent;
public class Widget extends com.hybris.cockpitng.util.DefaultWidgetController {
    public String seen="none";
    @SocketEvent(socketId="existing") public void existing(String value){seen="existing:"+value;}
}
