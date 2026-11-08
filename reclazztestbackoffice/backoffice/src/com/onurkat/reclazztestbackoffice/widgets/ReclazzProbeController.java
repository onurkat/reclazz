package com.onurkat.reclazztestbackoffice.widgets;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Label;

import com.hybris.cockpitng.annotations.ViewEvent;
import com.hybris.cockpitng.util.DefaultWidgetController;

public class ReclazzProbeController extends DefaultWidgetController
{
	private static final long serialVersionUID = 1L;
	private Label status;
	private int sent;

	@Override
	public void initialize(final Component comp)
	{
		super.initialize(comp);
		status.setValue("ready");
	}

	@ViewEvent(componentID = "sendButton", eventName = Events.ON_CLICK)
	public void send()
	{
		sent++;
		status.setValue("sent " + sent + ", no handler");
		sendOutput("probeOutput", "ping-" + sent);
	}
}
