/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstClient;
import net.wurstclient.ai.PathFinder;
import net.wurstclient.ai.PathProcessor;
import net.wurstclient.events.PreMotionListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

@SearchTags({"mining interact", "auto open mining", "sparkle interact", "stealth", "silent interact", "auto confirm", "auto move", "ghost ai", "human-like", "pathfinder", "resilient"})
public final class CobbleMiningInteractHack extends Hack
	implements UpdateListener, PreMotionListener, RenderListener
{
	private final SliderSetting range = new SliderSetting("Range",
		"Range to look for mining spots.", 5, 1, 10, 0.1, ValueDisplay.DECIMAL);
	
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"Interaction mode.", Mode.values(), Mode.SNAP);
	
	private final SliderSetting smoothSpeed = new SliderSetting("Smooth speed",
		"Speed of the camera rotation.", 45, 1, 180, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting exitDelay = new SliderSetting("Exit delay",
		"Seconds to wait after a mining game.", 2.0, 0.0, 10.0, 0.5, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting autoConfirm = new CheckboxSetting("Auto-confirm",
		"Automatically clicks 'Yes' in dialogue.", true);
	
	private final SliderSetting confirmDelay = new SliderSetting("Confirm delay",
		"Seconds to wait before auto-confirming.", 0.5, 0.0, 5.0, 0.1, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting autoMove = new CheckboxSetting("Auto-move",
		"Automatically walks toward mining spots.", false);
	
	private final SliderSetting moveRange = new SliderSetting("Move range",
		"Max distance to look for mining spots.", 20, 5, 50, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting ghostAi = new CheckboxSetting("Ghost AI",
		"Hyper-realistic human-like behaviors.", false);
	
	private final CheckboxSetting socialInteraction = new CheckboxSetting("Social Interaction",
		"Looks at nearby players and sneaks.", true);
	
	private final SliderSetting humanJitter = new SliderSetting("Human Jitter",
		"Intensity of camera vibration.", 0.5, 0.0, 2.0, 0.1, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting debugMode = new CheckboxSetting("Debug Mode",
		"Shows the calculated path on screen.", false);
	
	private Method getSpotsMethod;
	private int cooldown;
	private long exitTimestamp;
	private boolean wasInMinigame;
	private BlockPos pendingSilentTarget;
	
	private Screen lastDialogScreen;
	private long dialogOpenTime;
	
	private PathFinder pathFinder;
	private PathProcessor pathProcessor;
	private BlockPos currentTargetOre;
	private BlockPos currentGoalStanding;
	
	private enum SocialState { NONE, STARE, SNEAK, RESUME }
	private SocialState socialState = SocialState.NONE;
	private Player trackedPlayer;
	private long socialStartTime;
	private final Random random = new Random();
	
	private final Map<BlockPos, Long> targetBlacklist = new HashMap<>(); // Recently mined
	private final Map<BlockPos, Long> unreachableTargets = new HashMap<>(); // Pathfinding failed
	
	public CobbleMiningInteractHack()
	{
		super("CobbleMiningInteract");
		setCategory(Category.OTHER);
		addSetting(range);
		addSetting(mode);
		addSetting(smoothSpeed);
		addSetting(exitDelay);
		addSetting(autoConfirm);
		addSetting(confirmDelay);
		addSetting(autoMove);
		addSetting(moveRange);
		addSetting(ghostAi);
		addSetting(socialInteraction);
		addSetting(humanJitter);
		addSetting(debugMode);
	}
	
	@Override
	protected void onEnable()
	{
		cooldown = 0;
		exitTimestamp = 0;
		wasInMinigame = false;
		pendingSilentTarget = null;
		lastDialogScreen = null;
		pathFinder = null;
		pathProcessor = null;
		currentTargetOre = null;
		currentGoalStanding = null;
		socialState = SocialState.NONE;
		targetBlacklist.clear();
		unreachableTargets.clear();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PreMotionListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PreMotionListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		stopMoving();
	}
	
	@Override
	public void onUpdate()
	{
		Screen screen = MC.screen;
		
		if(ghostAi.isChecked())
			updateSocial();
		
		if(socialState != SocialState.NONE)
		{
			if(pathProcessor != null) stopMoving();
			return;
		}
		
		if(autoConfirm.isChecked() && screen != null
			&& screen.getClass().getName().contains("DigDialogScreen"))
		{
			if(screen != lastDialogScreen)
			{
				lastDialogScreen = screen;
				dialogOpenTime = System.currentTimeMillis();
			}
			
			if(System.currentTimeMillis() - dialogOpenTime >= confirmDelay.getValue() * 1000)
			{
				confirmDig(screen);
				lastDialogScreen = null;
			}
		}
		else
			lastDialogScreen = null;
		
		boolean isInMinigame = screen != null && isMinigameScreen(screen);
		if(wasInMinigame && !isInMinigame)
			exitTimestamp = System.currentTimeMillis();
		
		wasInMinigame = isInMinigame;
		
		if(screen != null || System.currentTimeMillis() - exitTimestamp < exitDelay.getValue() * 1000)
		{
			if(pathProcessor != null) stopMoving();
			return;
		}
		
		if(cooldown > 0)
		{
			cooldown--;
			return;
		}
		
		// Map cleanup
		long now = System.currentTimeMillis();
		targetBlacklist.entrySet().removeIf(entry -> now - entry.getValue() > 10000);
		unreachableTargets.entrySet().removeIf(entry -> now - entry.getValue() > 30000); // 30s for failures
		
		try
		{
			if(getSpotsMethod == null)
			{
				Class<?> c = Class.forName("handyfon.pickaxeminigame.client.PickaxeminigameClient");
				getSpotsMethod = c.getDeclaredMethod("getSpotsForCurrentDimension");
				getSpotsMethod.setAccessible(true);
			}
			
			@SuppressWarnings("unchecked")
			Collection<BlockPos> spots = (Collection<BlockPos>)getSpotsMethod.invoke(null);
			if(spots == null || spots.isEmpty())
			{
				if(pathProcessor != null) stopMoving();
				return;
			}
			
			// Filter and Sort spots by distance
			double moveR = moveRange.getValue();
			List<BlockPos> validSpots = spots.stream()
				.filter(pos -> !targetBlacklist.containsKey(pos))
				.filter(pos -> !unreachableTargets.containsKey(pos))
				.filter(pos -> MC.player.distanceToSqr(Vec3.atCenterOf(pos)) <= moveR * moveR)
				.sorted(Comparator.comparingDouble(pos -> MC.player.distanceToSqr(Vec3.atCenterOf(pos))))
				.collect(Collectors.toList());
			
			if(validSpots.isEmpty())
			{
				if(pathProcessor != null) stopMoving();
				return;
			}
			
			// Iterative fallback search
			for(BlockPos target : validSpots)
			{
				double distRaw = MC.player.distanceToSqr(Vec3.atCenterOf(target));
				double interactR = range.getValue();
				
				if(distRaw <= interactR * interactR)
				{
					if(pathProcessor != null) stopMoving();
					handleTarget(target);
					return; // Success
				}
				else if(autoMove.isChecked())
				{
					if(tryAutoMove(target))
						return; // Success
					else
						unreachableTargets.put(target, System.currentTimeMillis());
				}
			}
			
			// If no target was successful
			if(pathProcessor != null) stopMoving();
			
		}catch(Exception e)
		{
			// e.printStackTrace();
		}
	}
	
	private void updateSocial()
	{
		if(!socialInteraction.isChecked()) return;
		long now = System.currentTimeMillis();
		
		switch(socialState)
		{
			case NONE:
				Player target = MC.level.players().stream()
					.filter(p -> p != MC.player && !p.isRemoved() && MC.player.distanceTo(p) < 10)
					.min(Comparator.comparingDouble(p -> MC.player.distanceTo(p)))
					.orElse(null);
				
				if(target != null && random.nextInt(100) < 5)
				{
					socialState = SocialState.STARE;
					trackedPlayer = target;
					socialStartTime = now;
				}
				break;
				
			case STARE:
				if(trackedPlayer == null || trackedPlayer.isRemoved() || MC.player.distanceTo(trackedPlayer) > 15)
				{
					socialState = SocialState.NONE;
					return;
				}
				
				Vec3 headPos = trackedPlayer.getEyePosition();
				Rotation needed = RotationUtils.getNeededRotations(headPos);
				Rotation next = RotationUtils.slowlyTurnTowards(needed, (float)smoothSpeed.getValue());
				MC.player.setYRot(next.yaw());
				MC.player.setXRot(next.pitch());
				
				if(now - socialStartTime > 1500)
				{
					socialState = SocialState.SNEAK;
					socialStartTime = now;
				}
				break;
				
			case SNEAK:
				MC.options.keyShift.setDown(true);
				if(now - socialStartTime > 200)
				{
					MC.options.keyShift.setDown(false);
					socialState = SocialState.RESUME;
					socialStartTime = now;
				}
				break;
				
			case RESUME:
				socialState = SocialState.NONE;
				trackedPlayer = null;
				break;
		}
	}
	
	private boolean tryAutoMove(BlockPos orePos)
	{
		BlockPos standingSpot = findStandingSpot(orePos);
		if(standingSpot == null)
			return false; // Try next ore
		
		if(currentTargetOre == null || !currentTargetOre.equals(orePos))
		{
			stopMoving();
			currentTargetOre = orePos;
			currentGoalStanding = standingSpot;
			pathFinder = new PathFinder(currentGoalStanding);
		}
		
		if(pathFinder != null)
		{
			if(!pathFinder.isDone() && !pathFinder.isFailed())
			{
				pathFinder.setThinkSpeed(2048);
				pathFinder.setThinkTime(500);
				pathFinder.think();
				
				if(pathFinder.isDone())
				{
					pathFinder.formatPath();
					pathProcessor = pathFinder.getProcessor();
				}
			}
			
			if(pathFinder.isFailed())
			{
				stopMoving();
				return false; // Try next ore
			}
		}
		
		if(pathProcessor != null && !pathProcessor.isDone())
		{
			pathProcessor.process();
			applyHumanJitter();
			if(random.nextInt(100) < 2)
				MC.player.swing(InteractionHand.MAIN_HAND);
			return true; // Occupied with this target
		}
		else if(pathProcessor != null && pathProcessor.isDone())
		{
			stopMoving();
			return false; // Reached goal, wait for onUpdate to handle interaction
		}
		
		return true; // Still thinking about this target
	}
	
	private void applyHumanJitter()
	{
		if(!ghostAi.isChecked()) return;
		float intensity = (float)humanJitter.getValue();
		if(intensity > 0)
		{
			MC.player.setYRot(MC.player.getYRot() + (random.nextFloat() - 0.5f) * intensity);
			MC.player.setXRot(MC.player.getXRot() + (random.nextFloat() - 0.5f) * intensity);
		}
	}
	
	private BlockPos findStandingSpot(BlockPos orePos)
	{
		// Increased radius to 6x6x6 for better coverage in complex areas
		for(int y = -4; y <= 6; y++)
		{
			for(int x = -6; x <= 6; x++)
			{
				for(int z = -6; z <= 6; z++)
				{
					BlockPos pos = orePos.offset(x, y, z);
					if(isValidStandingSpot(pos))
					{
						if(orePos.distSqr(pos) <= range.getValue() * range.getValue())
							return pos;
					}
				}
			}
		}
		return null;
	}
	
	private boolean isValidStandingSpot(BlockPos pos)
	{
		return isPassable(pos) && isPassable(pos.above()) && isSolid(pos.below());
	}
	
	private boolean isPassable(BlockPos pos)
	{
		BlockState state = BlockUtils.getState(pos);
		return !state.blocksMotion();
	}
	
	private boolean isSolid(BlockPos pos)
	{
		BlockState state = BlockUtils.getState(pos);
		return state.blocksMotion() && !(state.getBlock() instanceof net.minecraft.world.level.block.SignBlock);
	}
	
	private void handleTarget(BlockPos pos)
	{
		targetBlacklist.put(pos, System.currentTimeMillis());
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
					Rotation next = RotationUtils.slowlyTurnTowards(needed, (float)smoothSpeed.getValue());
					MC.player.setYRot(next.yaw());
					MC.player.setXRot(next.pitch());
				}
				break;
				
			case SILENT:
				WurstClient.INSTANCE.getRotationFaker().faceVectorPacket(hitVec);
				pendingSilentTarget = pos;
				break;
		}
	}
	
	@Override
	public void onPreMotion()
	{
		if(pendingSilentTarget == null) return;
		executeInteract(pendingSilentTarget);
		pendingSilentTarget = null;
		cooldown = 10;
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(debugMode.isChecked() && pathFinder != null && (pathFinder.isDone() || pathFinder.isFailed()))
			pathFinder.renderPath(matrixStack, true, true);
	}
	
	private void executeInteract(BlockPos pos)
	{
		Vec3 hitVec = Vec3.atCenterOf(pos);
		BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, pos, false);
		MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND, hitResult);
		MC.player.swing(InteractionHand.MAIN_HAND);
	}
	
	private void stopMoving()
	{
		PathProcessor.releaseControls();
		pathProcessor = null;
		pathFinder = null;
		currentTargetOre = null;
		currentGoalStanding = null;
	}
	
	private void confirmDig(Screen screen)
	{
		int buttonCount = 0;
		for(GuiEventListener child : screen.children())
		{
			if(child instanceof Button button)
			{
				buttonCount++;
				if(buttonCount == 2)
				{
					if(pathProcessor != null) stopMoving();
					button.onPress();
					return;
				}
			}
			else if(child instanceof AbstractWidget widget && widget.getClass().getName().endsWith("$1"))
			{
				buttonCount++;
				if(buttonCount == 2)
				{
					if(widget instanceof Button b)
					{
						if(pathProcessor != null) stopMoving();
						b.onPress();
					}
					return;
				}
			}
		}
	}
	
	private boolean isMinigameScreen(Screen screen)
	{
		return screen.getClass().getName().contains("MiningMinigameScreen");
	}
	
	private enum Mode
	{
		SNAP("Snap"),
		SMOOTH("Smooth"),
		SILENT("Silent");
		
		private final String name;
		private Mode(String name) { this.name = name; }
		@Override public String toString() { return name; }
	}
}
