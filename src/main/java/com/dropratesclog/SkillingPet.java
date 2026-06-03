package com.dropratesclog;

import java.util.List;

/** One skilling pet's data from skilling_pets.json: the skill it belongs to and the activities that roll for it. */
class SkillingPet
{
    String                  skill;   // e.g. "Mining"; maps to net.runelite.api.Skill by name
    List<SkillingPetSource> sources;
}
