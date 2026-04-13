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
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EspBoxSizeSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;
import net.wurstclient.util.RenderUtils.ColoredPoint;

@SearchTags({"cobblemon esp", "pokemon esp", "PokeESP", "poke esp"})
public final class CobblemonESPHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final EspStyleSetting style = new EspStyleSetting();
	
	private final EspBoxSizeSetting boxSize = new EspBoxSizeSetting(
		"\u00a7lAccurate\u00a7r mode shows the exact hitbox of each Pokemon.\n"
			+ "\u00a7lFancy\u00a7r mode shows slightly larger boxes that look better.");
	
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
	
	private final ArrayList<PokemonEntity> pokemonList = new ArrayList<>();
	
	public CobblemonESPHack()
	{
		super("CobblemonESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(boxSize);
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
		pokemonList.clear();
		
		Stream<PokemonEntity> stream = StreamSupport
			.stream(MC.level.entitiesForRendering().spliterator(), false)
			.filter(PokemonEntity.class::isInstance).map(e -> (PokemonEntity)e)
			.filter(e -> !e.isRemoved());
		
		List<String> whitelistList = getWhitelist();
		
		stream = stream.filter(e -> {
			Pokemon pokemon = e.getPokemon();
			String name = pokemon.getSpecies().getName().toLowerCase();
			
			// Global Whitelist check (ALWAYS show)
			if(!whitelistList.isEmpty() && whitelistList.contains(name))
				return true;
			
			// Invisible check
			if(e.isInvisible() && showInvisible.isChecked())
				return true;
			
			// Shiny check
			if(pokemon.getShiny() && filterShiny.isChecked())
				return true;
			
			// Rarity filters
			if(pokemon.isLegendary() && filterLegendary.isChecked())
				return true;
			
			if(pokemon.isMythical() && filterMythical.isChecked())
				return true;
			
			if(pokemon.isUltraBeast() && filterUltraBeast.isChecked())
				return true;
			
			if(pokemon.isUltraBeast()) // UB handled above, don't fall back to
										// rarity
				return false;
			
			// Fallback to rarity buckets for others
			if(filterCommon.isChecked())
				return true;
			if(filterUncommon.isChecked() || filterRare.isChecked()
				|| filterUltraRare.isChecked())
				return true;
			
			return false;
		});
		
		pokemonList.addAll(stream.collect(Collectors.toList()));
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
