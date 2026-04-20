/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayList;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EspBoxSizeSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;

@SearchTags({"cobbleloot esp", "pokeball esp", "mining esp"})
public final class CobbleLootEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final EspStyleSetting style = new EspStyleSetting();
	
	private final EspBoxSizeSetting boxSize = new EspBoxSizeSetting(
		"\u00a7lAccurate\u00a7r mode shows the exact hitbox.\n"
			+ "\u00a7lFancy\u00a7r mode shows larger boxes.");
	
	private final ColorSetting color =
		new ColorSetting("Color", "Highlight color.", Color.CYAN);
	
	private final TextFieldSetting entityNames = new TextFieldSetting(
		"Entity IDs", "Comma-separated list of entities to highlight.",
		"cobbleloots:loot_ball");
	
	private final ArrayList<Entity> loots = new ArrayList<>();
	private String[] targetIds = new String[]{"cobbleloots:loot_ball"};
	private String lastTargetString = "";
	
	public CobbleLootEspHack()
	{
		super("CobbleLootESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(boxSize);
		addSetting(color);
		addSetting(entityNames);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		String currentTargets = entityNames.getValue();
		if(!currentTargets.equals(lastTargetString))
		{
			lastTargetString = currentTargets;
			String[] splits = currentTargets.toLowerCase().split(",");
			for(int i = 0; i < splits.length; i++)
			{
				splits[i] = splits[i].trim();
			}
			targetIds = splits;
		}
		
		loots.clear();
		for(Entity entity : MC.level.entitiesForRendering())
		{
			if(isTarget(entity))
			{
				loots.add(entity);
			}
		}
	}
	
	private boolean isTarget(Entity entity)
	{
		String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
			.getKey(entity.getType()).toString().toLowerCase();
		String className = entity.getClass().getSimpleName().toLowerCase();
		
		for(String target : targetIds)
		{
			if(target.isEmpty())
				continue;
			if(id.contains(target) || className.contains(target))
				return true;
		}
		return false;
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.hasLines())
			event.cancel();
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		int lineColor = color.getColorI(0x80);
		
		if(style.hasBoxes())
		{
			double extraSize = boxSize.getExtraSize() / 2;
			
			ArrayList<AABB> boxes = new ArrayList<>(loots.size());
			for(Entity e : loots)
				boxes.add(EntityUtils.getLerpedBox(e, partialTicks)
					.move(0, extraSize, 0).inflate(extraSize));
			
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, lineColor, false);
		}
		
		if(style.hasLines())
		{
			ArrayList<Vec3> ends = new ArrayList<>(loots.size());
			for(Entity e : loots)
				ends.add(EntityUtils.getLerpedBox(e, partialTicks).getCenter());
			
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, lineColor,
				false);
		}
	}
}
