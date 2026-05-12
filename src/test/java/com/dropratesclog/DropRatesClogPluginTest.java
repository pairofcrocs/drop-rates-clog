package com.dropratesclog;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class DropRatesClogPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(DropRatesClogPlugin.class);
		RuneLite.main(args);
	}
}
