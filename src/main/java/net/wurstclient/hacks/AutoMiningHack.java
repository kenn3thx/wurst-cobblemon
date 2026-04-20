/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"auto mining", "mining minigame bot", "fossil miner",
	"item filter"})
public final class AutoMiningHack extends Hack implements UpdateListener
{
	private final SliderSetting delay = new SliderSetting("Delay",
		"Ticks between each hit.", 2, 1, 20, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting stopAtStability = new CheckboxSetting(
		"Stop safely",
		"Stops mining before the wall collapses to save the node for later.",
		true);
	
	private final SliderSetting minStability = new SliderSetting(
		"Min Stability", "Stop mining if wall stability is below this.", 5, 0,
		20, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting autoHammer =
		new CheckboxSetting("Auto Hammer",
			"Automatically uses the Hammer (3x3) for efficiency.", true);
	
	private final CheckboxSetting avoidBedrock = new CheckboxSetting(
		"Avoid Bedrock", "Never hit bedrock tiles to save stability.", true);
	
	// Item Filters
	private final Map<String, CheckboxSetting> filters = new HashMap<>();
	private final Map<Item, Integer> inventorySnapshot = new HashMap<>();
	private boolean wasInGame;
	
	private int cooldown;
	
	private Field gridField;
	private Field treasuresField;
	private Field collectedTreasuresField;
	private Field stoneHealthField;
	private Field dirtHealthField;
	private Field hasBedrockField;
	private Field stabilityRemainingField;
	private Method hitTileMethod;
	private Method setToolMethod;
	
	private Field treasureXField;
	private Field treasureYField;
	private Field treasureWidthField;
	private Field treasureHeightField;
	
	private Object toolPickaxe;
	private Object toolHammer;
	
	public AutoMiningHack()
	{
		super("AutoMining");
		setCategory(Category.OTHER);
		addSetting(delay);
		addSetting(stopAtStability);
		addSetting(minStability);
		addSetting(autoHammer);
		addSetting(avoidBedrock);
		
		initFilters();
	}
	
	private void initFilters()
	{
		// Fossils
		String[] fossils = {"helix_fossil", "dome_fossil", "old_amber",
			"root_fossil", "claw_fossil", "armor_fossil", "cover_fossil",
			"plume_fossil", "jaw_fossil", "sail_fossil"};
		for(String f : fossils)
			addFilter("Fossil: " + f, "cobblemon:" + f);
		
		// Tera Shards
		String[] types = {"fire", "water", "grass", "electric", "ice",
			"fighting", "poison", "ground", "flying", "psychic", "bug", "rock",
			"ghost", "dragon", "dark", "steel", "fairy", "stellar"};
		for(String t : types)
			addFilter("Tera: " + t, "mega_showdown:" + t + "_tera_shard");
		
		// Evolution Stones
		String[] stones = {"thunder_stone", "leaf_stone", "moon_stone",
			"sun_stone", "shiny_stone", "dusk_stone", "dawn_stone",
			"fire_stone", "water_stone"};
		for(String s : stones)
			addFilter("Stone: " + s, "cobblemon:" + s);
		
		// Mega Items
		addFilter("Mega: Blank stone", "mega_showdown:blank_mega_stone");
		addFilter("Mega: Key stone", "mega_showdown:key_stone");
		addFilter("Mega: Z-Crystal", "mega_showdown:blank_z_crystal");
		addFilter("Mega: Wishing star", "mega_showdown:wishing_star");
		
		// Ores
		addFilter("Ore: Diamond", "minecraft:diamond");
		addFilter("Ore: Emerald", "minecraft:emerald");
		addFilter("Ore: Gold", "minecraft:gold_ingot");
		addFilter("Ore: Iron", "minecraft:iron_ingot");
		addFilter("Ore: Netherite", "minecraft:netherite_scrap");
		
		// Tumblestones
		addFilter("Other: Tumblestone", "cobblemon:tumblestone");
		addFilter("Other: Sky Tumblestone", "cobblemon:sky_tumblestone");
		addFilter("Other: Black Tumblestone", "cobblemon:black_tumblestone");
	}
	
	private void addFilter(String name, String id)
	{
		CheckboxSetting setting = new CheckboxSetting(name,
			"Automatically drop " + name + " after mining.", false);
		filters.put(id, setting);
		addSetting(setting);
	}
	
	@Override
	protected void onEnable()
	{
		cooldown = 0;
		wasInGame = false;
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
		Screen screen = MC.screen;
		boolean isInGame = screen != null && isMinigameScreen(screen);
		
		// Handle screen state change
		if(isInGame && !wasInGame)
			takeInventorySnapshot();
		else if(!isInGame && wasInGame)
			dropUnwantedItems();
		
		wasInGame = isInGame;
		
		if(cooldown > 0)
		{
			cooldown--;
			return;
		}
		
		if(!isInGame)
			return;
		
		try
		{
			if(gridField == null)
			{
				gridField = screen.getClass().getDeclaredField("grid");
				gridField.setAccessible(true);
			}
			
			Object grid = gridField.get(screen);
			if(grid == null)
				return;
			
			if(treasuresField == null)
				initReflection(grid);
			
			int stability = stabilityRemainingField.getInt(grid);
			if(stopAtStability.isChecked()
				&& stability < minStability.getValue())
				return;
			
			if(stability <= 0)
				return;
			
			List<?> treasures = (List<?>)treasuresField.get(grid);
			Set<?> collected = (Set<?>)collectedTreasuresField.get(grid);
			
			if(treasures == null)
				return;
			
			int[][] stoneHealth = (int[][])stoneHealthField.get(grid);
			int[][] dirtHealth = (int[][])dirtHealthField.get(grid);
			boolean[][] hasBedrock = (boolean[][])hasBedrockField.get(grid);
			int width = stoneHealth[0].length;
			int height = stoneHealth.length;
			
			for(Object treasure : treasures)
			{
				if(collected != null && collected.contains(treasure))
					continue;
				
				if(treasureXField == null)
					initTreasureReflection(treasure);
				
				int tx = treasureXField.getInt(treasure);
				int ty = treasureYField.getInt(treasure);
				int tw = treasureWidthField.getInt(treasure);
				int th = treasureHeightField.getInt(treasure);
				
				for(int x = tx; x < tx + tw; x++)
				{
					for(int y = ty; y < ty + th; y++)
					{
						if(stoneHealth[y][x] <= 0 && dirtHealth[y][x] <= 0)
							continue;
						
						if(avoidBedrock.isChecked() && hasBedrock[y][x])
							continue;
						
						if(autoHammer.isChecked() && stability >= 3)
						{
							if(tryHammer(grid, x, y, stoneHealth, dirtHealth,
								hasBedrock, width, height))
							{
								cooldown = (int)delay.getValue();
								return;
							}
						}
						
						setToolMethod.invoke(grid, toolPickaxe);
						hitTileMethod.invoke(grid, x, y);
						cooldown = (int)delay.getValue();
						return;
					}
				}
			}
			
		}catch(Exception e)
		{
			// e.printStackTrace();
		}
	}
	
	private void takeInventorySnapshot()
	{
		inventorySnapshot.clear();
		for(int i = 0; i < 36; i++)
		{
			ItemStack stack = MC.player.getInventory().getItem(i);
			if(stack.isEmpty())
				continue;
			
			Item item = stack.getItem();
			inventorySnapshot.put(item,
				inventorySnapshot.getOrDefault(item, 0) + stack.getCount());
		}
	}
	
	private void dropUnwantedItems()
	{
		Map<Item, Integer> currentInv = new HashMap<>();
		for(int i = 0; i < 36; i++)
		{
			ItemStack stack = MC.player.getInventory().getItem(i);
			if(stack.isEmpty())
				continue;
			
			Item item = stack.getItem();
			currentInv.put(item,
				currentInv.getOrDefault(item, 0) + stack.getCount());
		}
		
		for(Map.Entry<Item, Integer> entry : currentInv.entrySet())
		{
			Item item = entry.getKey();
			int currentCount = entry.getValue();
			int oldCount = inventorySnapshot.getOrDefault(item, 0);
			
			if(currentCount > oldCount)
			{
				String id = BuiltInRegistries.ITEM.getKey(item).toString();
				CheckboxSetting filter = filters.get(id);
				
				if(filter != null && filter.isChecked())
					dropAllStacksOf(item);
			}
		}
	}
	
	private void dropAllStacksOf(Item item)
	{
		for(int i = 0; i < 36; i++)
		{
			ItemStack stack = MC.player.getInventory().getItem(i);
			if(!stack.isEmpty() && stack.getItem() == item)
			{
				// Drop the stack
				// Slot indices for handleInventoryMouseClick:
				// 0-8 are crafting/armor, 9-35 are main inventory, 36-44 are
				// hotbar
				// Forge/Vanilla slot mapping context:
				// In Wurst/Minecraft 1.21.1, index 'i' for
				// player.getInventory().getItem(i)
				// maps to different network slots.
				int networkSlot = i < 9 ? i + 36 : i;
				MC.gameMode.handleInventoryMouseClick(0, networkSlot, 1,
					ClickType.THROW, MC.player);
			}
		}
	}
	
	private boolean tryHammer(Object grid, int x, int y, int[][] stone,
		int[][] dirt, boolean[][] bedrock, int w, int h) throws Exception
	{
		for(int dx = -1; dx <= 1; dx++)
		{
			for(int dy = -1; dy <= 1; dy++)
			{
				int nx = x + dx;
				int ny = y + dy;
				if(nx < 0 || ny < 0 || nx >= w || ny >= h)
					continue;
				
				if(bedrock[ny][nx])
					return false;
			}
		}
		
		setToolMethod.invoke(grid, toolHammer);
		hitTileMethod.invoke(grid, x, y);
		return true;
	}
	
	private void initReflection(Object grid) throws Exception
	{
		Class<?> gc = grid.getClass();
		treasuresField = gc.getDeclaredField("treasures");
		treasuresField.setAccessible(true);
		
		collectedTreasuresField = gc.getDeclaredField("collectedTreasures");
		collectedTreasuresField.setAccessible(true);
		
		stoneHealthField = gc.getDeclaredField("stoneHealth");
		stoneHealthField.setAccessible(true);
		
		dirtHealthField = gc.getDeclaredField("dirtHealth");
		dirtHealthField.setAccessible(true);
		
		hasBedrockField = gc.getDeclaredField("hasBedrock");
		hasBedrockField.setAccessible(true);
		
		stabilityRemainingField = gc.getDeclaredField("stabilityRemaining");
		stabilityRemainingField.setAccessible(true);
		
		hitTileMethod = gc.getDeclaredMethod("hitTile", int.class, int.class);
		hitTileMethod.setAccessible(true);
		
		setToolMethod = gc.getDeclaredMethod("setTool",
			Class.forName("handyfon.pickaxeminigame.MiningGrid$Tool"));
		setToolMethod.setAccessible(true);
		
		Class<?> tc = Class.forName("handyfon.pickaxeminigame.MiningGrid$Tool");
		toolPickaxe = tc.getField("STONE_PICKAXE").get(null);
		toolHammer = tc.getField("DIAMOND_PICKAXE").get(null);
	}
	
	private void initTreasureReflection(Object treasure) throws Exception
	{
		Class<?> tc = treasure.getClass();
		treasureXField = tc.getDeclaredField("x");
		treasureXField.setAccessible(true);
		treasureYField = tc.getDeclaredField("y");
		treasureYField.setAccessible(true);
		treasureWidthField = tc.getDeclaredField("width");
		treasureWidthField.setAccessible(true);
		treasureHeightField = tc.getDeclaredField("height");
		treasureHeightField.setAccessible(true);
	}
	
	private boolean isMinigameScreen(Screen screen)
	{
		return screen.getClass().getName().contains("MiningMinigameScreen");
	}
}
