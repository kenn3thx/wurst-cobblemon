/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.lang.reflect.Method;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

@SearchTags({"mining interact", "auto open mining", "sparkle interact"})
public final class CobbleMiningInteractHack extends Hack
	implements UpdateListener
{
	private final SliderSetting range =
		new SliderSetting("Range", "How far to reach for mining spots.", 5, 1,
			6, 0.1, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting autoRotate =
		new CheckboxSetting("Auto Rotate",
			"Automatically face the mining spot before interacting.", true);
	
	private final CheckboxSetting ignoreWalls =
		new CheckboxSetting("Ignore Walls",
			"Interact with spots even if they are behind blocks.", true);
	
	private final SliderSetting delay = new SliderSetting("Delay",
		"Ticks between each interaction.", 20, 1, 100, 1, ValueDisplay.INTEGER);
	
	private int cooldown;
	private Method getSpotsMethod;
	private boolean reflectionFailed;
	
	public CobbleMiningInteractHack()
	{
		super("CobbleMiningInteract");
		setCategory(Category.OTHER);
		addSetting(range);
		addSetting(autoRotate);
		addSetting(ignoreWalls);
		addSetting(delay);
	}
	
	@Override
	protected void onEnable()
	{
		cooldown = 0;
		reflectionFailed = false;
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
		
		if(MC.screen != null || reflectionFailed)
			return;
		
		try
		{
			if(getSpotsMethod == null)
			{
				Class<?> clazz = Class.forName(
					"handyfon.pickaxeminigame.client.PickaxeminigameClient");
				getSpotsMethod =
					clazz.getDeclaredMethod("getSpotsForCurrentDimension");
				getSpotsMethod.setAccessible(true);
			}
			
			@SuppressWarnings("unchecked")
			Set<BlockPos> spots = (Set<BlockPos>)getSpotsMethod.invoke(null);
			if(spots == null || spots.isEmpty())
				return;
			
			BlockPos closest = null;
			double closestDistSq = Double.MAX_VALUE;
			double maxDistSq = Math.pow(range.getValue(), 2);
			
			for(BlockPos pos : spots)
			{
				double distSq = MC.player.distanceToSqr(Vec3.atCenterOf(pos));
				if(distSq <= maxDistSq && distSq < closestDistSq)
				{
					closest = pos;
					closestDistSq = distSq;
				}
			}
			
			if(closest != null)
			{
				interact(closest);
				cooldown = (int)delay.getValue();
			}
			
		}catch(Exception e)
		{
			reflectionFailed = true;
		}
	}
	
	private void interact(BlockPos pos)
	{
		Vec3 hitVec = Vec3.atCenterOf(pos);
		
		if(autoRotate.isChecked())
		{
			Rotation needed = RotationUtils.getNeededRotations(hitVec);
			MC.player.setYRot(needed.yaw());
			MC.player.setXRot(needed.pitch());
		}
		
		BlockHitResult hitResult =
			new BlockHitResult(hitVec, Direction.UP, pos, false);
		
		MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND, hitResult);
		MC.player.swing(InteractionHand.MAIN_HAND);
	}
}
