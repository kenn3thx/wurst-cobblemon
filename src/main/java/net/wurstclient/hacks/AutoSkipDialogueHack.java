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

import net.minecraft.client.gui.screens.Screen;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"auto skip dialogue", "skip conversation", "npc dialogue skip",
	"auto skip conversation npc"})
public final class AutoSkipDialogueHack extends Hack implements UpdateListener
{
	private final CheckboxSetting instantSkip = new CheckboxSetting(
		"Instant Skip",
		"Skips everything as fast as possible until an option appears.", false);
	private final SliderSetting skipSpeed = new SliderSetting("Skip Speed",
		"Ticks between each skip click.\n"
			+ "Lower = faster skip, but may cause issues.\n"
			+ "Only used if Instant Skip is off.",
		2, 1, 20, 1, ValueDisplay.INTEGER);
	
	private int cooldown;
	
	public AutoSkipDialogueHack()
	{
		super("AutoSkipDialogue");
		setCategory(Category.OTHER);
		addSetting(instantSkip);
		addSetting(skipSpeed);
	}
	
	@Override
	protected void onEnable()
	{
		cooldown = 0;
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
		if(cooldown > 0)
		{
			cooldown--;
			return;
		}
		
		Screen screen = MC.screen;
		if(screen == null || !isDialogueScreen(screen))
			return;
		
		// 1. Force text animation to finish instantly
		Boolean gibberDone = getGibberDone(screen);
		if(gibberDone != null && !gibberDone)
		{
			setGibberDone(screen, true);
			if(!instantSkip.isChecked())
			{
				cooldown = 1;
				return;
			}
		}
		
		// 2. Check if option buttons are visible (final choices)
		List<?> options = getOptionsWidgets(screen);
		if(options != null && !options.isEmpty())
			return;
		
		// 3. No options yet -> click to advance to next dialogue page
		double middleX = screen.width / 2.0;
		double bottomY = screen.height - 20.0;
		double middleY = screen.height / 2.0;
		
		screen.mouseClicked(middleX, bottomY, 0);
		screen.mouseReleased(middleX, bottomY, 0);
		
		screen.mouseClicked(middleX, middleY, 0);
		screen.mouseReleased(middleX, middleY, 0);
		
		cooldown = instantSkip.isChecked() ? 0 : (int)skipSpeed.getValue();
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
