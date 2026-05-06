/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.lang.reflect.Method;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;

@SearchTags({"tide auto fish", "auto fishing minigame", "tide minigame",
	"stardew fishing"})
public final class AutoTideFishHack extends Hack
	implements UpdateListener, net.wurstclient.events.RenderListener
{
	private final CheckboxSetting perfectCatch = new CheckboxSetting(
		"Perfect Catch only",
		"Only simulates clicks when the marker is in the 'Perfect' zone (dead center).",
		true);
	
	private Class<?> overlayClass;
	private Method isActiveMethod;
	private Method getPositionMethod;
	private Method interactMethod;
	private java.lang.reflect.Field areaField;
	
	private static AutoTideFishHack INSTANCE;
	
	public AutoTideFishHack()
	{
		super("AutoTideFish");
		INSTANCE = this;
		setCategory(Category.OTHER);
		addSetting(perfectCatch);
	}
	
	public static boolean isMinigameActive()
	{
		if(INSTANCE == null || INSTANCE.isActiveMethod == null)
			return false;
		try
		{
			return (Boolean)INSTANCE.isActiveMethod.invoke(null);
		}catch(Exception e)
		{
			return false;
		}
	}
	
	@Override
	protected void onEnable()
	{
		try
		{
			if(overlayClass == null)
			{
				overlayClass = Class.forName(
					"com.li64.tide.client.gui.overlays.CatchMinigameOverlay");
				
				isActiveMethod = overlayClass.getDeclaredMethod("isActive");
				isActiveMethod.setAccessible(true);
				
				getPositionMethod =
					overlayClass.getDeclaredMethod("getMinigamePosition");
				getPositionMethod.setAccessible(true);
				
				interactMethod = overlayClass.getDeclaredMethod("interact");
				interactMethod.setAccessible(true);
				
				areaField = overlayClass.getDeclaredField("area");
				areaField.setAccessible(true);
			}
		}catch(Exception e)
		{
			// The Tide mod isn't installed or matching signature.
		}
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(net.wurstclient.events.RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(net.wurstclient.events.RenderListener.class, this);
	}
	
	@Override
	public void onRender(com.mojang.blaze3d.vertex.PoseStack matrixStack,
		float partialTicks)
	{
		processMinigame();
	}
	
	@Override
	public void onUpdate()
	{
		processMinigame();
	}
	
	private void processMinigame()
	{
		if(overlayClass == null)
			return;
		
		try
		{
			boolean isActive = (Boolean)isActiveMethod.invoke(null);
			if(!isActive)
				return;
			
			float position = (Float)getPositionMethod.invoke(null);
			float area = (Float)areaField.get(null);
			float absPos = Math.abs(position);
			
			// Use the actual 'area' field for 100% catch rate.
			// If Perfect Catch is on, use a slightly tighter threshold within
			// the mod's 0.1f limit.
			float threshold =
				perfectCatch.isChecked() ? Math.min(area, 0.09f) : area;
			
			if(absPos <= threshold)
			{
				interactMethod.invoke(null);
			}
			
		}catch(Exception e)
		{
			// Ignore reflections error mid-game
		}
	}
}
