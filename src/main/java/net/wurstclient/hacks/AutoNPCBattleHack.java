/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.lang.reflect.Field;
import java.util.List;
import java.util.stream.StreamSupport;

import net.minecraft.client.gui.components.AbstractWidget;
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
import net.wurstclient.util.ChatUtils;

@SearchTags({"auto npc battle", "ui clicker", "cobblemon npc"})
public final class AutoNPCBattleHack extends Hack implements UpdateListener
{
	private final TextFieldSetting npcName =
		new TextFieldSetting("NPC Name", "Target NPC.", "");
	private final CheckboxSetting autoTalk =
		new CheckboxSetting("Auto Talk", "Auto interact.", true);
	private final SliderSetting talkRange = new SliderSetting("Range",
		"Talk distance.", 4.0, 1.0, 6.0, 0.1, ValueDisplay.DECIMAL);
	private final CheckboxSetting debugMode =
		new CheckboxSetting("Debug Mode", "Detailed interaction logs.", true);
	
	private int talkTimer;
	private int actionCooldown;
	
	// Battle button animation tracking
	private boolean battleButtonSeen = false;
	private int battleButtonWaitTicks = 0;
	
	public AutoNPCBattleHack()
	{
		super("AutoNPCBattle");
		setCategory(Category.OTHER);
		addSetting(npcName);
		addSetting(autoTalk);
		addSetting(talkRange);
		addSetting(debugMode);
	}
	
	@Override
	protected void onEnable()
	{
		talkTimer = 0;
		actionCooldown = 0;
		battleButtonSeen = false;
		battleButtonWaitTicks = 0;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	private Class<?> cachedScreenClass;
	private boolean isDialogueScreenCached;
	
	private Field gibberDoneField;
	private Field optionsWidgetsField;
	
	@Override
	public void onUpdate()
	{
		if(talkTimer > 0)
			talkTimer--;
		if(actionCooldown > 0)
			actionCooldown--;
		
		Screen screen = MC.screen;
		
		if(screen != null && isDialogueScreen(screen))
		{
			try
			{
				handleDialoguePureUI(screen);
			}catch(Throwable t)
			{
				if(debugMode.isChecked() && actionCooldown <= 0)
					ChatUtils
						.error("Safeguard: " + t.getClass().getSimpleName());
			}
			return;
		}
		
		// Reset battle button tracking when dialogue closes
		battleButtonSeen = false;
		battleButtonWaitTicks = 0;
		
		if(screen == null && autoTalk.isChecked() && talkTimer <= 0)
			findAndTalkToNPC();
	}
	
	private void findAndTalkToNPC()
	{
		// Only check every 10 ticks to reduce lag
		talkTimer = 10;
		
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
			talkTimer = 40;
		}
	}
	
	private void handleDialoguePureUI(Screen screen)
	{
		if(actionCooldown > 0)
			return;
		
		// 1. FORCE TEXT TO FINISH RENDERING
		Boolean gibberDone = getGibberDone(screen);
		if(gibberDone != null && !gibberDone)
			setGibberDone(screen, true);
		
		// 2. CHECK FOR BATTLE BUTTON
		List<?> options = getOptionsWidgets(screen);
		if(options != null && !options.isEmpty())
		{
			String battleText =
				I18n.get("cobblemon.ui.dialogue.battle").toLowerCase();
			for(Object opt : options)
			{
				if(opt instanceof AbstractWidget widget)
				{
					String text = widget.getMessage().getString()
						.replaceAll("§[0-9a-fklmnor]", "").toLowerCase();
					if(text.contains(battleText) || text.contains("battle"))
					{
						// ANIMATION LOCK FIX:
						// First time we see the button -> start wait timer
						if(!battleButtonSeen)
						{
							battleButtonSeen = true;
							battleButtonWaitTicks = 0;
							if(debugMode.isChecked())
								ChatUtils.message(
									"§e[UI] Battle button found, waiting for animation...");
							return;
						}
						
						// Count ticks since we first saw the button
						battleButtonWaitTicks++;
						
						// Wait at least 10 ticks (0.5s) for animation
						if(battleButtonWaitTicks < 10)
							return;
						
						// Check if widget is actually active/ready
						if(!widget.active)
						{
							if(debugMode.isChecked())
								ChatUtils.message(
									"§e[UI] Button not active yet, waiting...");
							return;
						}
						
						if(debugMode.isChecked())
							ChatUtils.message(
								"§a[UI] Clicking Battle (gentle, after "
									+ battleButtonWaitTicks + " ticks)");
							
						// GENTLE CLICK: Only use screen.mouseClicked
						// This is exactly what Minecraft does when you
						// physically click your mouse
						double cx = widget.getX() + (widget.getWidth() / 2.0);
						double cy = widget.getY() + (widget.getHeight() / 2.0);
						
						screen.mouseClicked(cx, cy, 0);
						screen.mouseReleased(cx, cy, 0);
						
						actionCooldown = 30;
						battleButtonSeen = false;
						battleButtonWaitTicks = 0;
						return;
					}
				}
			}
		}
		
		// 3. SKIP DIALOGUE (no battle option yet)
		if(options == null || options.isEmpty())
		{
			// Reset battle button tracking if options disappear
			battleButtonSeen = false;
			battleButtonWaitTicks = 0;
			
			if(debugMode.isChecked())
				ChatUtils.message("§e[UI] Clicking Screen to Skip");
			
			double middleX = screen.width / 2.0;
			double bottomY = screen.height - 20.0;
			double middleY = screen.height / 2.0;
			
			screen.mouseClicked(middleX, bottomY, 0);
			screen.mouseReleased(middleX, bottomY, 0);
			
			screen.mouseClicked(middleX, middleY, 0);
			screen.mouseReleased(middleX, middleY, 0);
			
			actionCooldown = 15;
		}
	}
	
	private boolean isDialogueScreen(Screen screen)
	{
		Class<?> currClass = screen.getClass();
		if(cachedScreenClass != currClass)
		{
			cachedScreenClass = currClass;
			String className = currClass.getName();
			isDialogueScreenCached = className.contains("DialogueScreen")
				|| className.contains("DialogueGui");
			
			// Reset reflection fields for new screen type
			gibberDoneField = null;
			optionsWidgetsField = null;
		}
		return isDialogueScreenCached;
	}
	
	private boolean isCobblemonNPC(Entity e)
	{
		Class<?> clazz = e.getClass();
		String className = clazz.getName();
		return className.contains("npc") || className.contains("trainer");
	}
	
	private Boolean getGibberDone(Screen screen)
	{
		try
		{
			if(gibberDoneField == null)
			{
				gibberDoneField = findField(screen.getClass(), "gibberDone");
				if(gibberDoneField != null)
					gibberDoneField.setAccessible(true);
			}
			if(gibberDoneField != null)
				return (Boolean)gibberDoneField.get(screen);
		}catch(Exception e)
		{}
		return null;
	}
	
	private void setGibberDone(Screen screen, boolean value)
	{
		try
		{
			if(gibberDoneField != null)
				gibberDoneField.set(screen, value);
		}catch(Exception e)
		{}
	}
	
	private List<?> getOptionsWidgets(Screen screen)
	{
		try
		{
			if(optionsWidgetsField == null)
			{
				optionsWidgetsField =
					findField(screen.getClass(), "dialogueOptionWidgets");
				if(optionsWidgetsField != null)
					optionsWidgetsField.setAccessible(true);
			}
			if(optionsWidgetsField != null)
				return (List<?>)optionsWidgetsField.get(screen);
		}catch(Exception e)
		{}
		return null;
	}
	
	private Field findField(Class<?> clazz, String fieldName)
	{
		while(clazz != null && clazz != Object.class)
		{
			try
			{
				return clazz.getDeclaredField(fieldName);
			}catch(NoSuchFieldException e)
			{
				clazz = clazz.getSuperclass();
			}
		}
		return null;
	}
}
