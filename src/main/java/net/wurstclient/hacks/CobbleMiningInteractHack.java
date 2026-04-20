/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.lang.reflect.Method;
import java.util.Collection;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstClient;
import net.wurstclient.events.PreMotionListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

@SearchTags({"mining interact", "auto open mining", "sparkle interact", "stealth", "silent interact"})
public final class CobbleMiningInteractHack extends Hack
	implements UpdateListener, PreMotionListener
{
	private final SliderSetting range = new SliderSetting("Range",
		"Range to look for mining spots.", 5, 1, 10, 0.1, ValueDisplay.DECIMAL);
	
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"description.wurst.setting.cobblemininginteract.mode", Mode.values(),
		Mode.SNAP);
	
	private final SliderSetting smoothSpeed = new SliderSetting("Smooth speed",
		"Speed of the camera rotation (for Smooth mode).", 20, 1, 90, 1,
		ValueDisplay.INTEGER);
	
	private final SliderSetting exitDelay = new SliderSetting("Exit delay",
		"Seconds to wait after finishing a mining game before opening a new one.",
		2.0, 0.0, 10.0, 0.5, ValueDisplay.DECIMAL);
	
	private Method getSpotsMethod;
	private int cooldown;
	private long exitTimestamp;
	private boolean wasInMinigame;
	private BlockPos pendingSilentTarget;
	
	public CobbleMiningInteractHack()
	{
		super("CobbleMiningInteract");
		setCategory(Category.OTHER);
		addSetting(range);
		addSetting(mode);
		addSetting(smoothSpeed);
		addSetting(exitDelay);
	}
	
	@Override
	protected void onEnable()
	{
		cooldown = 0;
		exitTimestamp = 0;
		wasInMinigame = false;
		pendingSilentTarget = null;
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PreMotionListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PreMotionListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		Screen screen = MC.screen;
		boolean isInMinigame = screen != null && isMinigameScreen(screen);
		
		if(wasInMinigame && !isInMinigame)
			exitTimestamp = System.currentTimeMillis();
		
		wasInMinigame = isInMinigame;
		
		if(System.currentTimeMillis() - exitTimestamp < exitDelay.getValue()
			* 1000)
			return;
		
		if(cooldown > 0)
		{
			cooldown--;
			return;
		}
		
		try
		{
			if(getSpotsMethod == null)
			{
				Class<?> c = Class.forName(
					"handyfon.pickaxeminigame.client.PickaxeminigameClient");
				getSpotsMethod =
					c.getDeclaredMethod("getSpotsForCurrentDimension");
				getSpotsMethod.setAccessible(true);
			}
			
			@SuppressWarnings("unchecked")
			Collection<BlockPos> spots =
				(Collection<BlockPos>)getSpotsMethod.invoke(null);
			if(spots == null || spots.isEmpty())
				return;
			
			BlockPos closest = null;
			double closestDist = Double.MAX_VALUE;
			
			for(BlockPos pos : spots)
			{
				double dist = MC.player.distanceToSqr(Vec3.atCenterOf(pos));
				double r = range.getValue();
				if(dist < closestDist && dist <= r * r)
				{
					closest = pos;
					closestDist = dist;
				}
			}
			
			if(closest != null)
				handleTarget(closest);
			
		}catch(Exception e)
		{
			// e.printStackTrace();
		}
	}
	
	private void handleTarget(BlockPos pos)
	{
		Vec3 hitVec = Vec3.atCenterOf(pos);
		Rotation needed = RotationUtils.getNeededRotations(hitVec);
		
		switch(mode.getSelected())
		{
			case SNAP:
				MC.player.setYRot(needed.yaw());
				MC.player.setXRot(needed.pitch());
				executeInteract(pos);
				cooldown = 10;
				break;
				
			case SMOOTH:
				if(RotationUtils.isAlreadyFacing(needed))
				{
					executeInteract(pos);
					cooldown = 10;
				}
				else
				{
					Rotation next = RotationUtils.slowlyTurnTowards(needed,
						(float)smoothSpeed.getValue());
					MC.player.setYRot(next.yaw());
					MC.player.setXRot(next.pitch());
				}
				break;
				
			case SILENT:
				WurstClient.INSTANCE.getRotationFaker().faceVectorPacket(hitVec);
				pendingSilentTarget = pos;
				// Interaction happens in onPreMotion
				break;
		}
	}
	
	@Override
	public void onPreMotion()
	{
		if(pendingSilentTarget == null)
			return;
		
		executeInteract(pendingSilentTarget);
		pendingSilentTarget = null;
		cooldown = 10;
	}
	
	private void executeInteract(BlockPos pos)
	{
		Vec3 hitVec = Vec3.atCenterOf(pos);
		BlockHitResult hitResult =
			new BlockHitResult(hitVec, Direction.UP, pos, false);
		
		MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND, hitResult);
		MC.player.swing(InteractionHand.MAIN_HAND);
	}
	
	private boolean isMinigameScreen(Screen screen)
	{
		return screen.getClass().getName().contains("MiningMinigameScreen");
	}
	
	private enum Mode
	{
		SNAP("Snap (Original)"),
		SMOOTH("Smooth"),
		SILENT("Silent (Stealth)");
		
		private final String name;
		
		private Mode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
