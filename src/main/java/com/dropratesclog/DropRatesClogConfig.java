package com.dropratesclog;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("dropratesclog")
public interface DropRatesClogConfig extends Config
{
    @ConfigItem(
        keyName = "maxSources",
        name = "Max sources shown",
        description = "Maximum number of drop sources to display (0 = show all)"
    )
    default int maxSources()
    {
        return 5;
    }
}
