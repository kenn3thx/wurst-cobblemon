/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.util.RenderUtils;

@SearchTags({"cobblemining esp", "mining spot esp", "sparkle esp"})
public final class CobbleMiningEspHack extends Hack
	implements UpdateListener, RenderListener
{
	private final EspStyleSetting style = new EspStyleSetting();
	private final ColorSetting color = new ColorSetting("Color",
		"Highlight color for mining spots.", Color.CYAN);
	
	private final ArrayList<BlockPos> spots = new ArrayList<>();
	
	private Field clientActiveSpotsField;
	private Method getSpotsForCurrentDimensionMethod;
	private boolean reflectionFailed = false;
	
	public CobbleMiningEspHack()
	{
		super("CobbleMiningESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(color);
	}
	
	@Override
	protected void onEnable()
	{
		reflectionFailed = false;
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(reflectionFailed)
			return;
		
		spots.clear();
		try
		{
			if(getSpotsForCurrentDimensionMethod == null)
			{
				Class<?> clazz = Class.forName(
					"handyfon.pickaxeminigame.client.PickaxeminigameClient");
				getSpotsForCurrentDimensionMethod =
					clazz.getDeclaredMethod("getSpotsForCurrentDimension");
				getSpotsForCurrentDimensionMethod.setAccessible(true);
			}
			
			@SuppressWarnings("unchecked")
			Set<BlockPos> dimensionSpots =
				(Set<BlockPos>)getSpotsForCurrentDimensionMethod.invoke(null);
			
			if(dimensionSpots != null)
				spots.addAll(dimensionSpots);
			
		}catch(Exception e)
		{
			reflectionFailed = true;
		}
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(spots.isEmpty())
			return;
		
		int lineColor = color.getColorI(0x80);
		
		if(style.hasBoxes())
		{
			ArrayList<AABB> boxes = new ArrayList<>(spots.size());
			for(BlockPos pos : spots)
				boxes.add(new AABB(pos));
			
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, lineColor, false);
		}
		
		if(style.hasLines())
		{
			ArrayList<Vec3> ends = new ArrayList<>(spots.size());
			for(BlockPos pos : spots)
				ends.add(Vec3.atCenterOf(pos));
			
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, lineColor,
				false);
		}
	}
}
