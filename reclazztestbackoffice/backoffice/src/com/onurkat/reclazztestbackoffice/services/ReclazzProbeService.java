package com.onurkat.reclazztestbackoffice.services;

/**
 * A Backoffice-context bean, so bean reloads in the Backoffice web
 * application have a subject.
 */
public class ReclazzProbeService
{
	public String greeting()
	{
		return "v1";
	}
}
