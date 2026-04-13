/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.MouseUpdateListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EspBoxSizeSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;
import net.wurstclient.util.RenderUtils.ColoredPoint;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

@SearchTags({"cobblemon hunter", "pokemon hunter", "poke hunter",
	"cobblemon esp"})
public final class CobblemonHunterHack extends Hack implements UpdateListener,
	MouseUpdateListener, CameraTransformViewBobbingListener, RenderListener
{
	private final EspStyleSetting style = new EspStyleSetting();
	
	private final EspBoxSizeSetting boxSize = new EspBoxSizeSetting(
		"\u00a7lAccurate\u00a7r mode shows the exact hitbox of each Pokemon.\n"
			+ "\u00a7lFancy\u00a7r mode shows slightly larger boxes that look better.");
	
	private final CheckboxSetting filterWildOnly =
		new CheckboxSetting("Wild Only", "Only highlight wild Pokemon.", true);
	
	private final CheckboxSetting filterShiny =
		new CheckboxSetting("Show Shiny", "Highlight shiny Pokemon.", true);
	
	private final CheckboxSetting filterLegendary = new CheckboxSetting(
		"Show Legendary", "Highlight legendary Pokemon.", true);
	
	private final CheckboxSetting filterMythical = new CheckboxSetting(
		"Show Mythical", "Highlight mythical Pokemon.", true);
	
	private final CheckboxSetting filterUltraBeast = new CheckboxSetting(
		"Show Ultra-Beast", "Highlight Ultra Beasts.", true);
	
	private final CheckboxSetting filterUltraRare = new CheckboxSetting(
		"Show Ultra-Rare", "Highlight ultra-rare Pokemon.", true);
	
	private final CheckboxSetting filterRare =
		new CheckboxSetting("Show Rare", "Highlight rare Pokemon.", true);
	
	private final CheckboxSetting filterUncommon = new CheckboxSetting(
		"Show Uncommon", "Highlight uncommon Pokemon.", true);
	
	private final CheckboxSetting filterCommon =
		new CheckboxSetting("Show Common", "Highlight common Pokemon.", false);
	
	private final CheckboxSetting showInvisible =
		new CheckboxSetting("Show Invisible", "Show invisible Pokemon.", true);
	
	private final TextFieldSetting whitelist =
		new TextFieldSetting("Global Whitelist",
			"Comma-separated list of Pokemon names to ALWAYS highlight.\n"
				+ "Overrides rarity settings.",
			"");
	
	private final CheckboxSetting autoAim = new CheckboxSetting("Auto Aim",
		"Automatically look at high-priority Pokemon.", false);
	
	private final CheckboxSetting autoFollow =
		new CheckboxSetting("Auto Follow", "Run towards the target.", false);
	
	private final SliderSetting followDistance = new SliderSetting(
		"Follow Distance", "Distance to maintain from target.", 2.0, 1.0, 10.0,
		0.5, ValueDisplay.DECIMAL);
	
	private final SliderSetting rotationSpeed =
		new SliderSetting("Rotation Speed", 600, 10, 3600, 10,
			ValueDisplay.DEGREES.withSuffix("/s"));
	
	private final ArrayList<PokemonEntity> pokemonList = new ArrayList<>();
	private PokemonEntity target;
	private float nextYaw;
	private float nextPitch;
	private boolean wasFollowing;
	
	public CobblemonHunterHack()
	{
		super("CobblemonHunter");
		setCategory(Category.OTHER);
		addSetting(style);
		addSetting(boxSize);
		addSetting(filterWildOnly);
		addSetting(filterShiny);
		addSetting(filterLegendary);
		addSetting(filterMythical);
		addSetting(filterUltraBeast);
		addSetting(filterUltraRare);
		addSetting(filterRare);
		addSetting(filterUncommon);
		addSetting(filterCommon);
		addSetting(showInvisible);
		addSetting(whitelist);
		addSetting(autoAim);
		addSetting(autoFollow);
		addSetting(followDistance);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(MouseUpdateListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(MouseUpdateListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		target = null;
		if(wasFollowing)
		{
			MC.options.keyUp.setDown(false);
			MC.options.keyJump.setDown(false);
			wasFollowing = false;
		}
	}
	
	@Override
	public void onUpdate()
	{
		pokemonList.clear();
		target = null;
		
		Stream<PokemonEntity> stream = StreamSupport
			.stream(MC.level.entitiesForRendering().spliterator(), false)
			.filter(PokemonEntity.class::isInstance).map(e -> (PokemonEntity)e)
			.filter(e -> !e.isRemoved());
		
		List<String> whitelistList = getWhitelist();
		
		// 1. Process all entities to build rendering list and find candidate
		// targets
		List<PokemonEntity> candidates = stream.filter(e -> {
			Pokemon pokemon = e.getPokemon();
			String name = pokemon.getSpecies().getName().toLowerCase();
			
			// 1.1 Wild Only filter (affects both ESP and Targeting)
			if(filterWildOnly.isChecked() && !pokemon.isWild())
				return false;
			
			// 1.2 Rendering / Selection logic
			boolean shouldRender = false;
			
			// Global Whitelist check
			if(!whitelistList.isEmpty() && whitelistList.contains(name))
				shouldRender = true;
			
			// Invisible check
			else if(e.isInvisible() && showInvisible.isChecked())
				shouldRender = true;
			
			// Rarity/Shiny filters
			else if(pokemon.getShiny() && filterShiny.isChecked())
				shouldRender = true;
			else if(pokemon.isLegendary() && filterLegendary.isChecked())
				shouldRender = true;
			else if(pokemon.isMythical() && filterMythical.isChecked())
				shouldRender = true;
			else if(pokemon.isUltraBeast() && filterUltraBeast.isChecked())
				shouldRender = true;
			else if(pokemon.hasLabels("ultra-rare")
				&& filterUltraRare.isChecked())
				shouldRender = true;
			else if(pokemon.hasLabels("rare") && filterRare.isChecked())
				shouldRender = true;
			else if(pokemon.hasLabels("uncommon") && filterUncommon.isChecked())
				shouldRender = true;
			else if(filterCommon.isChecked())
				shouldRender = true;
			
			if(shouldRender)
				pokemonList.add(e);
			
			return shouldRender;
		}).collect(Collectors.toList());
		
		// 2. Select the best target according to priority scoring
		target = candidates.stream().max(Comparator.comparingDouble(e -> {
			Pokemon p = e.getPokemon();
			double score = 0;
			
			if(p.isLegendary())
				score += 10000;
			else if(p.isMythical())
				score += 9000;
			else if(p.isUltraBeast())
				score += 8000;
			else if(p.hasLabels("ultra-rare"))
				score += 1000;
			else if(p.hasLabels("rare"))
				score += 500;
			
			if(p.getShiny())
				score += 5000;
			
			// Penalty for distance
			score -= MC.player.distanceTo(e) * 10;
			
			return score;
		})).orElse(null);
		
		// 3. Automation (Aim & Follow)
		if(target != null)
		{
			// Auto Aim
			if(autoAim.isChecked())
			{
				Rotation needed = RotationUtils
					.getNeededRotations(target.getBoundingBox().getCenter());
				
				Rotation next = RotationUtils.slowlyTurnTowards(needed,
					rotationSpeed.getValueI() / 20F);
				nextYaw = next.yaw();
				nextPitch = next.pitch();
			}
			
			// Auto Follow
			if(autoFollow.isChecked())
			{
				double horizDistSq = MC.player.distanceToSqr(target.getX(),
					MC.player.getY(), target.getZ());
				double followDistSq = Math.pow(followDistance.getValue(), 2);
				
				if(horizDistSq > followDistSq)
				{
					MC.options.keyUp.setDown(true);
					MC.player.setSprinting(true);
					wasFollowing = true;
				}else if(wasFollowing)
				{
					MC.options.keyUp.setDown(false);
					wasFollowing = false;
				}
				
				// Auto Jump
				if(MC.player.horizontalCollision && MC.player.onGround())
					MC.player.jumpFromGround();
			}
		}else
		{
			// Reset movement if target is lost
			if(wasFollowing)
			{
				MC.options.keyUp.setDown(false);
				MC.options.keyJump.setDown(false);
				wasFollowing = false;
			}
		}
	}
	
	@Override
	public void onMouseUpdate(MouseUpdateEvent event)
	{
		if(target == null || !autoAim.isChecked() || MC.player == null)
			return;
		
		float curYaw = MC.player.getYRot();
		float curPitch = MC.player.getXRot();
		float diffYaw = nextYaw - curYaw;
		float diffPitch = nextPitch - curPitch;
		
		event.setDeltaX(event.getDefaultDeltaX() + diffYaw);
		event.setDeltaY(event.getDefaultDeltaY() + diffPitch);
	}
	
	private List<String> getWhitelist()
	{
		String value = whitelist.getValue().trim().toLowerCase();
		if(value.isEmpty())
			return new ArrayList<>();
		
		return Arrays.asList(value.split(",")).stream().map(String::trim)
			.collect(Collectors.toList());
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
		if(style.hasBoxes())
		{
			double extraSize = boxSize.getExtraSize() / 2;
			
			ArrayList<ColoredBox> boxes = new ArrayList<>(pokemonList.size());
			for(PokemonEntity e : pokemonList)
			{
				AABB box = EntityUtils.getLerpedBox(e, partialTicks)
					.move(0, extraSize, 0).inflate(extraSize);
				boxes.add(new ColoredBox(box, getColor(e)));
			}
			
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, false);
		}
		
		if(style.hasLines())
		{
			ArrayList<ColoredPoint> ends = new ArrayList<>(pokemonList.size());
			for(PokemonEntity e : pokemonList)
			{
				Vec3 point =
					EntityUtils.getLerpedBox(e, partialTicks).getCenter();
				ends.add(new ColoredPoint(point, getColor(e)));
			}
			
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, false);
		}
	}
	
	private int getColor(PokemonEntity e)
	{
		Pokemon pokemon = e.getPokemon();
		
		if(pokemon.getShiny())
			return 0xFFFFFF55; // Yellow
			
		if(pokemon.isLegendary() || pokemon.isMythical())
			return 0xFFFF5555; // Red
			
		if(pokemon.isUltraBeast())
			return 0xFFAA00AA; // Purple
			
		// Default to distance-based color if no specific condition met
		float f = MC.player.distanceTo(e) / 20F;
		float[] rgb = {Math.max(0, 1 - f), Math.min(1, f), 1};
		return RenderUtils.toIntColor(rgb, 0.5F);
	}
}
