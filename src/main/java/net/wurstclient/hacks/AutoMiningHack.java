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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import net.wurstclient.events.PreMotionListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

@SearchTags({"auto mining", "mining minigame bot", "fossil miner",
	"item filter", "stealth dropper"})
public final class AutoMiningHack extends Hack
	implements UpdateListener, PreMotionListener
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
	
	private final CheckboxSetting continuousDrop =
		new CheckboxSetting("Continuous Drop",
			"Automatically drops unwanted items whenever they are picked up.",
			true);
	
	private final SliderSetting dropDelay = new SliderSetting("Drop Delay",
		"Seconds to wait between dropping items.", 3.0, 0.5, 10.0, 0.5,
		ValueDisplay.DECIMAL);
	
	private final CheckboxSetting stealthDropper = new CheckboxSetting(
		"Stealth Dropper",
		"Tosses items into open space using silent rotations to avoid re-pickup.",
		true);
	
	private final CheckboxSetting enableAutoDrop = new CheckboxSetting(
		"Enable Auto Drop", "Master toggle for item cleanup.", true);
	
	private final SliderSetting minItemsToDrop = new SliderSetting(
		"Min Items to Drop",
		"Minimum amount of trash needed to trigger a social cleanup session.",
		5, 1, 15, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting itemInterval = new SliderSetting(
		"Item Interval", "Delay between dropping individual items in seconds.",
		0.3, 0.1, 2.0, 0.1, ValueDisplay.DECIMAL);
	
	private long lastDropTime;
	private int cooldown;
	private boolean wasInGame;
	
	private final Map<String, CheckboxSetting> masterFilters =
		new LinkedHashMap<>();
	private final Map<String, CheckboxSetting> filters = new HashMap<>();
	private final Map<Item, Integer> inventorySnapshot = new HashMap<>();
	
	private enum DropPhase
	{
		IDLE,
		DROPPING
	}
	
	private DropPhase dropPhase = DropPhase.IDLE;
	private final List<Integer> dropQueue = new ArrayList<>();
	
	private int lastStability = 20;
	
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
	private Field treasureItemField;
	private Field treasureRarityField;
	
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
		addSetting(enableAutoDrop);
		addSetting(continuousDrop);
		addSetting(minItemsToDrop);
		addSetting(itemInterval);
		addSetting(dropDelay);
		
		initFilters();
	}
	
	private void initFilters()
	{
		// Fossils Category
		CheckboxSetting masterFossil =
			addMasterFilter("Drop All: Fossils", "fossils");
		String[] fossils =
			{"helix_fossil", "dome_fossil", "old_amber", "root_fossil",
				"claw_fossil", "armor_fossil", "cover_fossil", "plume_fossil",
				"jaw_fossil", "sail_fossil", "skull_fossil", "fossilized_bird",
				"fossilized_fish", "fossilized_dino", "fossilized_drake"};
		for(String f : fossils)
		{
			String name = f.replace("_", " ");
			addFilter("Fossil: " + name, "cobblemon:" + f, masterFossil);
		}
		
		// Tera Category
		CheckboxSetting masterTera =
			addMasterFilter("Drop All: Tera Shards", "tera");
		String[] types = {"fire", "water", "grass", "electric", "ice",
			"fighting", "poison", "ground", "flying", "psychic", "bug", "rock",
			"ghost", "dragon", "dark", "steel", "fairy", "stellar", "normal"};
		for(String t : types)
			addFilter("Tera: " + t, "mega_showdown:" + t + "_tera_shard",
				masterTera);
		
		// Stones Category
		CheckboxSetting masterStone =
			addMasterFilter("Drop All: Stones", "stones");
		String[] stones = {"thunder_stone", "leaf_stone", "moon_stone",
			"sun_stone", "shiny_stone", "dusk_stone", "dawn_stone",
			"fire_stone", "water_stone", "ice_stone", "everstone"};
		for(String s : stones)
		{
			String name = s.replace("_", " ");
			addFilter("Stone: " + name, "cobblemon:" + s, masterStone);
		}
		
		// Gems Category
		CheckboxSetting masterGem = addMasterFilter("Drop All: Gems", "gems");
		for(String t : types)
		{
			if(t.equals("stellar"))
				continue;
			addFilter("Gem: " + t, "cobblemon:" + t + "_gem", masterGem);
		}
		
		// Ores Category
		CheckboxSetting masterOre = addMasterFilter("Drop All: Ores", "ores");
		addFilter("Ore: Diamond", "minecraft:diamond", masterOre);
		addFilter("Ore: Emerald", "minecraft:emerald", masterOre);
		addFilter("Ore: Gold", "minecraft:gold_ingot", masterOre);
		addFilter("Ore: Iron", "minecraft:iron_ingot", masterOre);
		addFilter("Ore: Copper", "minecraft:copper_ingot", masterOre);
		addFilter("Ore: Redstone", "minecraft:redstone", masterOre);
		addFilter("Ore: Lapis", "minecraft:lapis_lazuli", masterOre);
		addFilter("Ore: Quartz", "minecraft:quartz", masterOre);
		addFilter("Ore: Coal", "minecraft:coal", masterOre);
		
		// Others Category
		CheckboxSetting masterOther =
			addMasterFilter("Drop All: Others", "others");
		addFilter("Other: Tumblestone", "cobblemon:tumblestone", masterOther);
		addFilter("Other: Sky Tumblestone", "cobblemon:sky_tumblestone",
			masterOther);
		addFilter("Other: Black Tumblestone", "cobblemon:black_tumblestone",
			masterOther);
		addFilter("Other: Cobblestone", "minecraft:cobblestone", masterOther);
		addFilter("Mega: Blank stone", "mega_showdown:blank_mega_stone",
			masterOther);
		addFilter("Mega: Key stone", "mega_showdown:key_stone", masterOther);
		addFilter("Mega: Z-Crystal", "mega_showdown:blank_z_crystal",
			masterOther);
		addFilter("Mega: Wishing star", "mega_showdown:wishing_star",
			masterOther);
		addFilter("Other: Amethyst Shard", "minecraft:amethyst_shard",
			masterOther);
		
		// Smithing Category
		CheckboxSetting masterSmith =
			addMasterFilter("Drop All: Smithing", "smithing");
		addFilter("Smith: Netherite Upgrade",
			"minecraft:netherite_upgrade_smithing_template", masterSmith);
		addFilter("Smith: Sentry Trim",
			"minecraft:sentry_armor_trim_smithing_template", masterSmith);
		addFilter("Smith: Vex Trim",
			"minecraft:vex_armor_trim_smithing_template", masterSmith);
	}
	
	private CheckboxSetting addMasterFilter(String name, String key)
	{
		CheckboxSetting master =
			new CheckboxSetting(name, "Wipe entire category.", false);
		masterFilters.put(key, master);
		addSetting(master);
		return master;
	}
	
	private void addFilter(String name, String id, CheckboxSetting master)
	{
		CheckboxSetting setting = new CheckboxSetting(name,
			"Automatically drop " + name + " after mining.", false);
		filters.put(id, setting);
		addSetting(setting);
		
		// Store master relation in the ID if needed, but here I'll use a prefix
		// logic in the loop
		// or better, a custom map for check.
	}
	
	@Override
	protected void onEnable()
	{
		cooldown = 0;
		wasInGame = false;
		dropPhase = DropPhase.IDLE;
		dropQueue.clear();
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
		boolean isInGame = screen != null && isMinigameScreen(screen);
		
		if(isInGame)
		{
			if(!wasInGame)
			{
				takeInventorySnapshot();
				if(enableAutoDrop.isChecked() && dropPhase == DropPhase.IDLE)
					dropUnwantedItems();
			}else if(enableAutoDrop.isChecked() && dropPhase == DropPhase.IDLE)
			{
				// We don't trigger by count anymore to avoid suspicious
				// reactions to items thrown by players.
				// The actual trigger is now handled inside the minigame loop
				// via stability reset.
			}
		}else if(wasInGame)
		{
			if(enableAutoDrop.isChecked() && dropPhase == DropPhase.IDLE)
				dropUnwantedItems();
		}
		
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
			
			// Detect New Floor: Stability resets to max (usually 20 or higher)
			// after being low
			if(stability > lastStability + 5 && stability >= 15
				&& dropPhase == DropPhase.IDLE)
			{
				if(enableAutoDrop.isChecked())
					dropUnwantedItems();
			}
			lastStability = stability;
			
			if(stopAtStability.isChecked()
				&& stability < minStability.getValue())
				return;
			
			if(stability <= 0)
				return;
			
			List<Object> treasures =
				new ArrayList<>((List<?>)treasuresField.get(grid));
			Set<?> collected = (Set<?>)collectedTreasuresField.get(grid);
			
			if(treasures == null || treasures.isEmpty())
				return;
			
			// Sort treasures by priority
			treasures.sort((t1, t2) -> {
				int p1 = getPriorityRank(t1);
				int p2 = getPriorityRank(t2);
				if(p1 != p2)
					return Integer.compare(p1, p2); // Lower rank = higher
													// priority
					
				// Same rank, compare rarity (Higher = better)
				try
				{
					int r1 = treasureRarityField.getInt(t1);
					int r2 = treasureRarityField.getInt(t2);
					return Integer.compare(r2, r1);
				}catch(Exception e)
				{
					return 0;
				}
			});
			
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
	
	public boolean isDropping()
	{
		return dropPhase != DropPhase.IDLE;
	}
	
	private void dropUnwantedItems()
	{
		dropQueue.clear();
		for(int i = 9; i < 45; i++)
		{
			ItemStack stack =
				MC.player.getInventory().getItem(i < 36 ? i : i - 36);
			if(stack.isEmpty())
				continue;
			
			if(isUnwanted(stack.getItem()))
				dropQueue.add(i);
		}
		
		if(!dropQueue.isEmpty())
			dropPhase = DropPhase.DROPPING;
	}
	
	@Override
	public void onPreMotion()
	{
		if(dropPhase == DropPhase.IDLE)
			return;
		
		if(dropQueue.isEmpty())
		{
			dropPhase = DropPhase.IDLE;
			return;
		}
		
		long now = System.currentTimeMillis();
		if(now - lastDropTime < itemInterval.getValue() * 1000)
			return;
		
		int slot = dropQueue.remove(0);
		dropStack(slot);
		lastDropTime = now;
		
		if(dropQueue.isEmpty())
			takeInventorySnapshot();
	}
	
	private Rotation findSafeDropRotation()
	{
		float yaw = MC.player.getYRot();
		
		// Directions to check: UP, BACK, UP-BACK, LEFT, RIGHT
		float[] yaws = {yaw, yaw + 180, yaw + 180, yaw + 90, yaw - 90};
		float[] pitches = {-90, 0, -45, 0, 0};
		
		Rotation bestRotation = new Rotation(yaw, -90); // Default UP
		double maxDist = 0;
		
		Vec3 start = RotationUtils.getEyesPos();
		
		for(int i = 0; i < yaws.length; i++)
		{
			Rotation rot = Rotation.wrapped(yaws[i], pitches[i]);
			Vec3 end = start.add(rot.toLookVec().scale(8));
			
			BlockHitResult hit = MC.level.clip(new ClipContext(start, end,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, MC.player));
			
			double dist = hit.getType() == HitResult.Type.MISS ? 8
				: hit.getLocation().distanceTo(start);
			
			if(dist > maxDist)
			{
				maxDist = dist;
				bestRotation = rot;
			}
			
			if(maxDist >= 8)
				break;
		}
		
		return bestRotation;
	}
	
	private int getPriorityRank(Object treasure)
	{
		try
		{
			if(treasureItemField == null)
				initTreasureReflection(treasure);
			
			Item item = (Item)treasureItemField.get(treasure);
			String id = BuiltInRegistries.ITEM.getKey(item).toString();
			
			// Group 1: Unknown items (not in our filter map)
			if(!filters.containsKey(id))
				return 0;
			
			// Group 2: Known good items
			if(!isUnwanted(item))
				return 1;
			
			// Group 3: Known trash
			return 2;
			
		}catch(Exception e)
		{
			return 1;
		}
	}
	
	private boolean isUnwanted(Item item)
	{
		if(!enableAutoDrop.isChecked())
			return false;
		
		String id = BuiltInRegistries.ITEM.getKey(item).toString();
		
		// Master category logic
		if(id.contains("fossil") || id.contains("old_amber"))
		{
			if(masterFilters.get("fossils").isChecked())
				return true;
		}
		
		if(id.contains("_stone"))
		{
			if(masterFilters.get("stones").isChecked())
				return true;
		}
		
		if(id.contains("_gem"))
		{
			if(masterFilters.get("gems").isChecked())
				return true;
		}else if(id.startsWith("mega_showdown:"))
		{
			if(id.contains("_tera_shard"))
				if(masterFilters.get("tera").isChecked())
					return true;
		}else if(id.endsWith("_smithing_template"))
		{
			if(masterFilters.get("smithing").isChecked())
				return true;
		}
		
		// Specific filters logic
		CheckboxSetting filter = filters.get(id);
		return filter != null && filter.isChecked();
	}
	
	private int countUnwantedItems()
	{
		int count = 0;
		for(int i = 0; i < 36; i++)
		{
			ItemStack stack = MC.player.getInventory().getItem(i);
			if(stack.isEmpty())
				continue;
			
			if(isUnwanted(stack.getItem()))
			{
				if(continuousDrop.isChecked())
					count++;
				else
				{
					int oldCount =
						inventorySnapshot.getOrDefault(stack.getItem(), 0);
					if(stack.getCount() > oldCount)
						count++;
				}
			}
		}
		return count;
	}
	
	private int countNewUnwantedItems()
	{
		int count = 0;
		for(int i = 0; i < 36; i++)
		{
			ItemStack stack = MC.player.getInventory().getItem(i);
			if(stack.isEmpty())
				continue;
			
			Item item = stack.getItem();
			if(isUnwanted(item))
			{
				int oldCount = inventorySnapshot.getOrDefault(item, 0);
				if(stack.getCount() > oldCount)
					count++;
			}
		}
		return count;
	}
	
	private void dropStack(int slot)
	{
		int networkSlot = slot < 9 ? slot + 36 : slot;
		MC.gameMode.handleInventoryMouseClick(0, networkSlot, 1,
			ClickType.THROW, MC.player);
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
		treasureItemField = tc.getDeclaredField("item");
		treasureItemField.setAccessible(true);
		treasureRarityField = tc.getDeclaredField("rarity");
		treasureRarityField.setAccessible(true);
	}
	
	private boolean isMinigameScreen(Screen screen)
	{
		return screen.getClass().getName().contains("MiningMinigameScreen");
	}
}
