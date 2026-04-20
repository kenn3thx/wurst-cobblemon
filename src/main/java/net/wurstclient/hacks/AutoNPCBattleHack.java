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
		Boolean gibberDone = (Boolean)getFieldValue(screen, "gibberDone");
		if(gibberDone != null && !gibberDone)
			setFieldValue(screen, "gibberDone", true);
		
		// 2. CHECK FOR BATTLE BUTTON
		List<?> options =
			(List<?>)getFieldValue(screen, "dialogueOptionWidgets");
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
		String className = screen.getClass().getName();
		return className.contains("DialogueScreen")
			|| className.contains("DialogueGui");
	}
	
	private boolean isCobblemonNPC(Entity e)
	{
		String className = e.getClass().getName();
		return className.contains("com.cobblemon.mod.common.entity.npc")
			|| className.contains("com.cobblemon.mod.common.entity.trainer");
	}
	
	private void setFieldValue(Object obj, String fieldName, Object value)
	{
		try
		{
			Class<?> clazz = obj.getClass();
			while(clazz != null && clazz != Object.class)
			{
				try
				{
					Field field = clazz.getDeclaredField(fieldName);
					field.setAccessible(true);
					field.set(obj, value);
					return;
				}catch(NoSuchFieldException e)
				{
					clazz = clazz.getSuperclass();
				}
			}
		}catch(Exception e)
		{}
	}
	
	private Object getFieldValue(Object obj, String fieldName)
	{
		try
		{
			Class<?> clazz = obj.getClass();
			while(clazz != null && clazz != Object.class)
			{
				try
				{
					Field field = clazz.getDeclaredField(fieldName);
					field.setAccessible(true);
					return field.get(obj);
				}catch(NoSuchFieldException e)
				{
					clazz = clazz.getSuperclass();
				}
			}
		}catch(Exception e)
		{}
		return null;
	}
}
