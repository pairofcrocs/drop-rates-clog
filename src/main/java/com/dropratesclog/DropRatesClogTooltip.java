package com.dropratesclog;

import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

import javax.inject.Inject;
import java.awt.Dimension;
import java.awt.Graphics2D;

public class DropRatesClogTooltip extends Overlay
{
    private final DropRatesClogPlugin plugin;
    private final TooltipManager tooltipManager;

    @Inject
    DropRatesClogTooltip(DropRatesClogPlugin plugin, TooltipManager tooltipManager)
    {
        this.plugin = plugin;
        this.tooltipManager = tooltipManager;
        setPosition(OverlayPosition.TOOLTIP);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        String text = plugin.buildActiveTooltip();
        if (text != null)
        {
            tooltipManager.add(new Tooltip(text));
        }
        return null;
    }
}
