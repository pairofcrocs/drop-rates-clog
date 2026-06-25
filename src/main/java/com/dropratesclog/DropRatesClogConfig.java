package com.dropratesclog;

import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

import java.awt.Color;

@ConfigGroup("dropratesclog")
public interface DropRatesClogConfig extends Config
{
    @ConfigSection(
        name = "Drop Rates",
        description = "Drop chances from the wiki",
        position = 0
    )
    String dropRatesSection = "dropRates";

    @ConfigSection(
        name = "Skilling Pet Chances",
        description = "Pet chances at your current level",
        position = 1
    )
    String skillingPetsSection = "skillingPets";

    @ConfigSection(
        name = "Acquirable Items",
        description = "Shops, rewards, and other ways to acquire each item",
        position = 2
    )
    String acquirableSection = "acquirable";

    @ConfigSection(
        name = "UI Colors",
        description = "Tooltip text colours",
        position = 200,
        closedByDefault = true
    )
    String uiSection = "ui";

    // --- Drop Rates section ---

    @ConfigItem(
        keyName = "showDropRates",
        name = "Show Drop Rates",
        description = "Show the Drop Rate section",
        section = dropRatesSection,
        position = 0
    )
    default boolean showDropRates()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showDropRateAsPercentage",
        name = "Show as percentage",
        description = "Show drop rates as a percentage instead of a ratio",
        section = dropRatesSection,
        position = 1
    )
    default boolean showDropRateAsPercentage()
    {
        return false;
    }

    @ConfigItem(
        keyName = "normaliseToOneInN",
        name = "Show as 1/N",
        description = "Normalise each drop fraction to 1/N (e.g. 90/18,014 → 1/200)",
        section = dropRatesSection,
        position = 2
    )
    default boolean normaliseToOneInN()
    {
        return true;
    }

    @Range(min = 0, max = 3)
    @ConfigItem(
        keyName = "oneInNDecimals",
        name = "1/N decimal places",
        description = "Decimals kept in the N of a 1/N rate (0 = whole number; e.g. 1 → 1/181.1)",
        section = dropRatesSection,
        position = 3
    )
    default int oneInNDecimals()
    {
        return 1;
    }

    @ConfigItem(
        keyName = "combineMultiRolls",
        name = "Combine multi-roll rates",
        description = "Merge rates rolled several times per kill (e.g. 3 × 1/6) into one; off keeps \"N × a/b\"",
        section = dropRatesSection,
        position = 4
    )
    default boolean combineMultiRolls()
    {
        return true;
    }

    @ConfigItem(
        keyName = "multiRollChancePerKill",
        name = "Multi-roll: chance per kill",
        description = "How a rate rolled several times per kill is combined — off: expected count (N × a/b); "
            + "on: chance of at least one per kill (1−(1−a/b)^N)",
        section = dropRatesSection,
        position = 5
    )
    default boolean multiRollChancePerKill()
    {
        return false;
    }

    // --- Skilling Pet Chances section ---

    @ConfigItem(
        keyName = "showSkillingPetChance",
        name = "Show Skilling Pet Chances",
        description = "Show per-action chance at your current level",
        section = skillingPetsSection,
        position = 0
    )
    default boolean showSkillingPetChance()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showOnlyPopularMethods",
        name = "Show popular methods only",
        description = "Filter to popular training methods (from the wiki training guides)",
        section = skillingPetsSection,
        position = 1
    )
    default boolean showOnlyPopularMethods()
    {
        return false;
    }

    // --- Acquirable Items section ---

    @ConfigItem(
        keyName = "showAcquirableItems",
        name = "Show Acquirable Items",
        description = "Show the Acquirable Items section",
        section = acquirableSection,
        position = 0
    )
    default boolean showAcquirableItems()
    {
        return true;
    }

    // --- UI Colors section ---

    @Alpha
    @ConfigItem(
        keyName = "headerColor",
        name = "Header color",
        description = "Section label, e.g. \"Drop Rate:\"",
        section = uiSection,
        position = 0
    )
    default Color headerColor()
    {
        return Color.WHITE;
    }

    @Alpha
    @ConfigItem(
        keyName = "itemNameColor",
        name = "Item name color",
        description = "The hovered item's name in the header, e.g. \"Fox Whistle\"",
        section = uiSection,
        position = 1
    )
    default Color itemNameColor()
    {
        return new Color(0xFFA21E);
    }

    @Alpha
    @ConfigItem(
        keyName = "rateColor",
        name = "Rate / detail color",
        description = "The yellow rate/cost/requirement value before each source",
        section = uiSection,
        position = 2
    )
    default Color rateColor()
    {
        return new Color(0xFFFF00);
    }

    @Alpha
    @ConfigItem(
        keyName = "sourceColor",
        name = "Source color",
        description = "Source / monster / activity names",
        section = uiSection,
        position = 3
    )
    default Color sourceColor()
    {
        return Color.WHITE;
    }

    // --- General (unsectioned, render below the four sections) ---

    @ConfigItem(
        keyName = "maxSources",
        name = "Max sources shown",
        description = "Max rate groups per section (0 = unlimited)",
        position = 100
    )
    default int maxSources()
    {
        return 7;
    }

    @ConfigItem(
        keyName = "hideCheckPopup",
        name = "Hide \"Check\" popup",
        description = "Hide the cursor's \"Check <item>\" indicator on clog items",
        position = 101
    )
    default boolean hideCheckPopup()
    {
        return true;
    }

    @ConfigItem(
        keyName = "hideTooltipIfObtained",
        name = "Hide tooltip if obtained",
        description = "Hide the tooltip for items you already own",
        position = 102
    )
    default boolean hideTooltipIfObtained()
    {
        return false;
    }
}
