/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.stream.StreamSupport;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;

@SearchTags({"auto npc battle", "auto talk", "npc battle", "cobblemon npc"})
public final class AutoNPCBattleHack extends Hack implements UpdateListener
{
	private final TextFieldSetting npcName = new TextFieldSetting("NPC Name",
		"The name of the NPC to automatically talk to.\n"
			+ "Leave empty to talk to any NPC.",
		"");
	
	private final CheckboxSetting autoTalk = new CheckboxSetting("Auto Talk",
		"Automatically talk to the NPC when in range.", true);
	
	private final SliderSetting talkRange = new SliderSetting("Talk Range",
		"The range at which to talk to the NPC.", 3.0, 1.0, 6.0, 0.1,
		ValueDisplay.DECIMAL);
	
	private final SliderSetting talkDelay = new SliderSetting("Talk Delay",
		"Wait this long before talking to an NPC again after closing a dialogue.",
		2.0, 0.0, 10.0, 0.5, ValueDisplay.DECIMAL.withSuffix("s"));
	
	private int talkTimer;
	private boolean wasDialogueOpen;
	
	public AutoNPCBattleHack()
	{
		super("AutoNPCBattle");
		setCategory(Category.OTHER);
		addSetting(npcName);
		addSetting(autoTalk);
		addSetting(talkRange);
		addSetting(talkDelay);
	}
	
	@Override
	protected void onEnable()
	{
		talkTimer = 0;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(talkTimer > 0)
			talkTimer--;
		
		Screen screen = MC.screen;
		
		// If a dialogue screen is open
		if(screen != null && isDialogueScreen(screen))
		{
			handleDialogue(screen);
			wasDialogueOpen = true;
			return;
		}
		
		if(wasDialogueOpen)
		{
			talkTimer = (int)(talkDelay.getValue() * 20);
			wasDialogueOpen = false;
		}
		
		// If no screen is open, look for NPC to talk to
		if(screen == null && autoTalk.isChecked() && talkTimer <= 0)
		{
			findAndTalkToNPC();
		}
	}
	
	private void findAndTalkToNPC()
	{
		String targetName = npcName.getValue().trim().toLowerCase();
		double rangeSq = Math.pow(talkRange.getValue(), 2);
		
		Entity target = StreamSupport
			.stream(MC.level.entitiesForRendering().spliterator(), false)
			.filter(e -> isCobblemonNPC(e))
			.filter(e -> targetName.isEmpty()
				|| e.getName().getString().toLowerCase().contains(targetName))
			.filter(e -> MC.player.distanceToSqr(e) <= rangeSq).findFirst()
			.orElse(null);
		
		if(target != null)
		{
			MC.gameMode.interact(MC.player, target, InteractionHand.MAIN_HAND);
			talkTimer = 20; // Wait 1 second before trying again
		}
	}
	
	private void handleDialogue(Screen screen)
	{
		// Detect "Battle" button based on localization
		String battleText = I18n.get("cobblemon.ui.dialogue.battle");
		
		// Modern Minecraft screens have renderables list (widened in Wurst)
		for(Renderable renderable : screen.renderables)
		{
			if(!(renderable instanceof AbstractWidget widget))
				continue;
			
			String widgetText = widget.getMessage().getString();
			
			// Click the "Battle" button
			if(widgetText.equalsIgnoreCase(battleText))
			{
				clickWidget(screen, widget);
				return;
			}
		}
		
		// If no battle button found, try to progress the dialogue by clicking
		// (simulating space/left click)
		// Usually Cobblemon dialogues progress on any click if there are no
		// options.
		// We'll simulate a click if the dialogue is "waiting for input"
		if(isWaitingForDialogueInput(screen))
		{
			// Simulate clicking anywhere in the screen center to progress
			MC.mouseHandler.onPress(MC.getWindow().getWindow(), 0, 1, 0); // Left
																			// Click
																			// Down
			MC.mouseHandler.onPress(MC.getWindow().getWindow(), 0, 0, 0); // Left
																			// Click
																			// Up
		}
	}
	
	private boolean isDialogueScreen(Screen screen)
	{
		String className = screen.getClass().getName();
		return className.contains("DialogueScreen")
			|| className.contains("DialogueGui");
	}
	
	private boolean isWaitingForDialogueInput(Screen screen)
	{
		// This is a heuristic. Usually, if there are no option buttons and it's
		// a DialogueScreen,
		// it's a text page waiting to be continued.
		// We avoid clicking if there are other interactive widgets that aren't
		// the "Battle" button.
		return true; // Simplified for Phase 1
	}
	
	private boolean isCobblemonNPC(Entity e)
	{
		String className = e.getClass().getName();
		// Common Cobblemon NPC entity classes
		return className.contains("com.cobblemon.mod.common.entity.npc")
			|| className.contains("com.cobblemon.mod.common.entity.trainer");
	}
	
	private void clickWidget(Screen screen, AbstractWidget widget)
	{
		double x = widget.getX() + widget.getWidth() / 2.0;
		double y = widget.getY() + widget.getHeight() / 2.0;
		screen.mouseClicked(x, y, 0); // 0 is Left Click
	}
}
