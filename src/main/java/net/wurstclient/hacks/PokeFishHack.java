/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.cobblemon.mod.common.entity.fishing.PokeRodFishingBobberEntity;
import com.cobblemon.mod.common.item.interactive.PokerodItem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.autofish.AutoFishDebugDraw;
import net.wurstclient.hacks.autofish.AutoFishRodSelector;
import net.wurstclient.hacks.autofish.FishingSpotManager;
import net.wurstclient.hacks.autofish.ShallowWaterWarningCheckbox;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"PokeFish", "poke fish", "cobblemon fish", "CobbleFish",
	"auto fishing", "PokeRod", "pokerod"})
public final class PokeFishHack extends Hack
	implements UpdateListener, PacketInputListener, RenderListener
{
	private final EnumSetting<BiteMode> biteMode = new EnumSetting<>(
		"Bite mode",
		"\u00a7lSound\u00a7r mode detects bites by listening for bite sounds (including Cobblemon notification sounds)."
			+ " This method is less accurate, but more resilient against anti-cheats.\n\n"
			+ "\u00a7lEntity\u00a7r mode detects bites by checking the fishing hook's entity update packet. "
			+ "Works with both vanilla and Cobblemon bobbers.",
		BiteMode.values(), BiteMode.SOUND);
	
	private final SliderSetting validRange = new SliderSetting("Valid range",
		"Any bites that occur outside of this range will be ignored.", 1.5,
		0.25, 8, 0.25, ValueDisplay.DECIMAL);
	
	private final SliderSetting catchDelay = new SliderSetting("Catch delay",
		"How long PokeFish will wait after a bite before reeling in.", 0, 0, 60,
		1, ValueDisplay.INTEGER.withSuffix(" ticks").withLabel(1, "1 tick"));
	
	private final SliderSetting retryDelay = new SliderSetting("Retry delay",
		"How long PokeFish will wait before trying again if casting/reeling fails.",
		15, 0, 100, 1,
		ValueDisplay.INTEGER.withSuffix(" ticks").withLabel(1, "1 tick"));
	
	private final SliderSetting patience = new SliderSetting("Patience",
		"How long PokeFish will wait for a bite before reeling in.", 60, 10,
		120, 1, ValueDisplay.INTEGER.withSuffix("s"));
	
	private final ShallowWaterWarningCheckbox shallowWaterWarning =
		new ShallowWaterWarningCheckbox();
	private final FishingSpotManager fishingSpots = new FishingSpotManager();
	private final AutoFishDebugDraw debugDraw =
		new AutoFishDebugDraw(validRange, fishingSpots);
	// We use the standard RodSelector for now, it should work if PokeRods
	// extend FishingRodItem
	private final AutoFishRodSelector rodSelector =
		new AutoFishRodSelector(this);
	
	private int castRodTimer;
	private int reelInTimer;
	private boolean biteDetected;
	
	public PokeFishHack()
	{
		super("PokeFish");
		setCategory(Category.OTHER);
		addSetting(biteMode);
		addSetting(validRange);
		addSetting(catchDelay);
		addSetting(retryDelay);
		addSetting(patience);
		debugDraw.getSettings().forEach(this::addSetting);
		rodSelector.getSettings().forEach(this::addSetting);
		addSetting(shallowWaterWarning);
	}
	
	@Override
	protected void onEnable()
	{
		castRodTimer = 0;
		reelInTimer = 0;
		biteDetected = false;
		rodSelector.reset();
		debugDraw.reset();
		fishingSpots.reset();
		shallowWaterWarning.reset();
		
		WURST.getHax().antiAfkHack.setEnabled(false);
		WURST.getHax().aimAssistHack.setEnabled(false);
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(RenderListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(AutoTideFishHack.isMinigameActive())
			return;
		
		if(castRodTimer > 0)
			castRodTimer--;
		if(reelInTimer > 0)
			reelInTimer--;
		
		if(!rodSelector.update())
			return;
		
		if(!isFishing())
		{
			if(castRodTimer > 0)
				return;
			
			reelInTimer = 20 * patience.getValueI();
			if(!fishingSpots.onCast())
				return;
			
			MC.startUseItem();
			castRodTimer = retryDelay.getValueI();
			return;
		}
		
		if(biteDetected)
		{
			shallowWaterWarning.checkWaterType();
			reelInTimer = catchDelay.getValueI();
			fishingSpots.onBite(MC.player.fishing);
			biteDetected = false;
		}else if(MC.player.fishing.getHookedIn() != null)
			reelInTimer = catchDelay.getValueI();
		
		if(reelInTimer == 0)
		{
			MC.startUseItem();
			reelInTimer = retryDelay.getValueI();
			castRodTimer = retryDelay.getValueI();
		}
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		switch(biteMode.getSelected())
		{
			case SOUND -> processSoundUpdate(event);
			case ENTITY -> processEntityUpdate(event);
		}
	}
	
	private void processSoundUpdate(PacketInputEvent event)
	{
		if(!(event.getPacket() instanceof ClientboundSoundPacket sound))
			return;
		
		ResourceLocation soundLoc =
			BuiltInRegistries.SOUND_EVENT.getKey(sound.getSound().value());
		
		if(soundLoc == null)
			return;
		
		ResourceLocation splashLoc = BuiltInRegistries.SOUND_EVENT
			.getKey(SoundEvents.FISHING_BOBBER_SPLASH);
		
		boolean isBiteSound = splashLoc.equals(soundLoc)
			|| soundLoc.getNamespace().equals("cobblemon")
				&& (soundLoc.getPath().contains("fishing.notification")
					|| soundLoc.getPath().contains("fishing.splash"));
		
		if(!isBiteSound)
			return;
		
		if(!isFishing())
			return;
		
		debugDraw.updateSoundPos(sound);
		
		Vec3 bobber = MC.player.fishing.position();
		double dx = Math.abs(sound.getX() - bobber.x());
		double dz = Math.abs(sound.getZ() - bobber.z());
		if(Math.max(dx, dz) > validRange.getValue())
			return;
		
		biteDetected = true;
	}
	
	private void processEntityUpdate(PacketInputEvent event)
	{
		if(!(event
			.getPacket() instanceof ClientboundSetEntityDataPacket update))
			return;
		
		var entity = MC.level.getEntity(update.id());
		if(!(entity instanceof FishingHook)
			&& !(entity instanceof PokeRodFishingBobberEntity))
			return;
		
		if(entity != MC.player.fishing)
			return;
		
		if(!isFishing())
			return;
		
		biteDetected = true;
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		debugDraw.render(matrixStack, partialTicks);
	}
	
	private boolean isFishing()
	{
		LocalPlayer player = MC.player;
		if(player == null || player.fishing == null
			|| player.fishing.isRemoved())
			return false;
		
		ItemStack stack = player.getMainHandItem();
		return stack.is(Items.FISHING_ROD)
			|| stack.getItem() instanceof FishingRodItem
			|| stack.getItem() instanceof PokerodItem || stack.getItem()
				.getClass().getName().contains("TideFishingRodItem");
	}
	
	private enum BiteMode
	{
		SOUND("Sound"),
		ENTITY("Entity");
		
		private final String name;
		
		private BiteMode(String name)
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
