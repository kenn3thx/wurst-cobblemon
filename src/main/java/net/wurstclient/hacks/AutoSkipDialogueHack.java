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
		Boolean gibberDone = (Boolean)getFieldValue(screen, "gibberDone");
		if(gibberDone != null && !gibberDone)
		{
			setFieldValue(screen, "gibberDone", true);
			if(!instantSkip.isChecked())
			{
				cooldown = 1;
				return;
			}
		} // <- Added closing brace here
		
		// 2. Check if option buttons are visible (final choices)
		// If options are showing, STOP skipping - let the user choose
		List<?> options =
			(List<?>)getFieldValue(screen, "dialogueOptionWidgets");
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
		String className = screen.getClass().getName();
		return className.contains("DialogueScreen")
			|| className.contains("DialogueGui");
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
