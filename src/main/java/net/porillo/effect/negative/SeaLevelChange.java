package net.porillo.effect.negative;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import lombok.Getter;
import net.porillo.GlobalWarming;
import net.porillo.effect.ClimateData;
import net.porillo.effect.SeaChange;
import net.porillo.effect.api.ClimateEffectType;
import net.porillo.effect.api.ListenerClimateEffect;
import net.porillo.effect.api.change.SerializableBlockChange;
import net.porillo.engine.ClimateEngine;
import net.porillo.engine.api.Distribution;
import net.porillo.engine.api.WorldClimateEngine;
import net.porillo.objects.SeaLevel;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;

import java.io.*;
import java.util.HashSet;
import java.util.Set;

import static org.bukkit.Material.*;

/**
 * Sea-level rise
 * - Two asynchronous, repeating tasks
 * 1) Add jobs to the stack (once the stack is empty)
 * 2) Apply any required changes
 * <p>
 * - Sea level will rise with the temperature
 * - Raised blocks are tagged with meta data
 * - When sea levels lower, the tagged blocks are reset
 * - Will not dry out lakes, rivers, irrigation, machines, etc.
 * - Considerations made for growing kelp, player changes, and
 * other events: blocks that drop, etc.
 */
@ClimateData(type = ClimateEffectType.SEA_LEVEL_RISE, isSerialized = true)
public class SeaLevelChange extends ListenerClimateEffect {

    @Getter private Distribution seaMap;
    private int queueTicks;
    private SeaLevel seaLevel;

    @Override
    public void serialize() {
        try {
            FileOutputStream fileOut = new FileOutputStream("plugins/GlobalWarming/seaLevel.dat");
            ObjectOutputStream out = new ObjectOutputStream(fileOut);
            out.writeObject(seaLevel);
            out.close();
            fileOut.close();
        } catch (IOException i) {
            i.printStackTrace();
        }
    }

    @Override
    public void deserialize() {
        try {
            FileInputStream fileIn = new FileInputStream("plugins/GlobalWarming/seaLevel.dat");
            ObjectInputStream in = new ObjectInputStream(fileIn);
            seaLevel = (SeaLevel) in.readObject();
            seaLevel.debug();
            in.close();
            fileIn.close();
        } catch (IOException i) {
            seaLevel = new SeaLevel();
        } catch (ClassNotFoundException c) {
            System.out.println("Serialized Data class not found");
            c.printStackTrace();
        }

        if (seaLevel == null) {
            this.seaLevel = new SeaLevel();
        }
    }

    /**
     * Update the queue with loaded-chunks once the queue is empty
     */
    private void startQueueLoader() {
        Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(GlobalWarming.getInstance(), () -> {
            for (World world : Bukkit.getWorlds()) {
                if (world.getEnvironment() == World.Environment.NORMAL) {
                    final WorldClimateEngine wce = ClimateEngine.getInstance().getClimateEngine(world.getUID());

                    if (wce != null && wce.isEffectEnabled(ClimateEffectType.SEA_LEVEL_RISE)) {
                        if (seaLevel.getDefaultLevel() == null) {
                            seaLevel.setDefaultLevel(world.getSeaLevel() - 1);
                            GlobalWarming.getInstance().getLogger().info("Default sea level: " + (world.getSeaLevel() - 1));
                        }

                        if (seaLevel.getCurrentLevel() == null) {
                            seaLevel.setCurrentLevel(world.getSeaLevel() - 1);
                        }

                        // Calculate the custom sea level based on the temperature. This is what we want.
                        final int customSeaLevel = getCustomSeaLevel(wce);

                        if (customSeaLevel > seaLevel.getCurrentLevel()) {
                            for (Chunk chunk : world.getLoadedChunks()) {
                                diffBlocks(chunk, SeaChange.UP);
                            }
                            System.out.println(String.format("%s, [ct:%d, cr:%d, def:%d]", "UP",
                                    customSeaLevel, seaLevel.getCurrentLevel(), seaLevel.getDefaultLevel()));

                            seaLevel.setCurrentLevel(customSeaLevel);
                            seaLevel.setChange(SeaChange.UP);
                        } else if (customSeaLevel < seaLevel.getCurrentLevel()) {
                            for (Chunk chunk : world.getLoadedChunks()) {
                                diffBlocks(chunk, SeaChange.DOWN);
                            }

                            System.out.println(String.format("%s, [ct:%d, cr:%d, def:%d]", "DOWN",
                                    customSeaLevel, seaLevel.getCurrentLevel(), seaLevel.getDefaultLevel()));

                            seaLevel.setCurrentLevel(customSeaLevel);
                            seaLevel.setChange(SeaChange.DOWN);
                        } else {
                            seaLevel.setChange(SeaChange.NONE);
                        }
                    }
                }
            }
        }, 0L, queueTicks);
    }

    public int getCustomSeaLevel(final WorldClimateEngine wce) {
        final int deltaSeaLevel = (int) seaMap.getValue(wce.getTemperature());
        return seaLevel.getDefaultLevel() + deltaSeaLevel;
    }


    private void diffBlocks(Chunk chunk, SeaChange change) {
        World world = chunk.getWorld();
        WorldClimateEngine climateEngine = ClimateEngine.getInstance().getClimateEngine(world.getUID());
        final int customSeaLevel = getCustomSeaLevel(climateEngine);

        if (change == SeaChange.UP) {
            //Scan chunk-blocks within the sea-level's range:
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    final int y = seaLevel.getDefaultLevel();
                    Block block = chunk.getBlock(x, y, z);

                    // Test a block at sea level in the chunk, if needed we fill above this block
                    if (block.getType() == WATER || block.getType() == AIR) {
                        fillTo(chunk, customSeaLevel, x, z, y);
                    } else if (block.getType() == ICE || block.getType() == PACKED_ICE) {
                        SerializableBlockChange sbc1 = new SerializableBlockChange(block, WATER);
                        seaLevel.getLocationHashChangeMap().put(sbc1.getHashcode(), sbc1);
                        block.setType(WATER, true);

                        fillTo(chunk, customSeaLevel, x, z, y);
                    }
                }
            }
        } else if (change == SeaChange.DOWN) {
            Set<Integer> remove = new HashSet<>();
            for (SerializableBlockChange sbc : seaLevel.getLocationHashChangeMap().values()) {
                if (sbc.getY() > customSeaLevel) {
                    remove.add(sbc.getHashcode());
                    sbc.getBukkitWorld().getBlockAt(sbc.getLocation()).setType(sbc.getFrom());
                }
            }

            for (Integer hash : remove) {
                seaLevel.getLocationHashChangeMap().remove(hash);
            }
        }

        System.out.println(String.format("[%d,%d] {%s:%d}", chunk.getX(), chunk.getZ(), change.name(), seaLevel.getLocationHashChangeMap().size()));
    }

    private void fillTo(Chunk chunk, int customSeaLevel, int x, int z, int y) {
        for (int yy = y + 1; yy <= customSeaLevel; yy++) {
            SerializableBlockChange sbc = new SerializableBlockChange(chunk.getBlock(x, yy, z), WATER);
            seaLevel.getLocationHashChangeMap().put(sbc.getHashcode(), sbc);
            chunk.getBlock(x, yy, z).setType(WATER, true);
        }
        seaLevel.getChunkSeaLevel().put(chunk.hashCode(), customSeaLevel);
    }

    /**
     * Replacing sea-level-blocks will remove them from the tracked set
     */
    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        seaLevel.getLocationHashChangeMap().remove(event.getBlockPlaced().getLocation().hashCode());
    }

    /**
     * Emptying a bucket (water or lava) will remove the adjacent block
     * from the tracked set
     */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerBucketEmpty(PlayerBucketEmptyEvent event) {
        Block adjacent = event.getBlockClicked().getRelative(event.getBlockFace());
        seaLevel.getLocationHashChangeMap().remove(adjacent.getLocation().hashCode());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockFromToEvent(BlockFromToEvent event) {
        Block block = event.getBlock();
        if (event.getToBlock().getType() == WATER) {
            World world = event.getBlock().getWorld();
            WorldClimateEngine wce = ClimateEngine.getInstance().getClimateEngine(world.getUID());

            if (wce != null && wce.isEffectEnabled(ClimateEffectType.SEA_LEVEL_RISE)) {
                if (seaLevel.getChange() == SeaChange.UP) {
                    seaLevel.getLocationHashChangeMap().put(block.getLocation().hashCode(),
                            new SerializableBlockChange(event.getToBlock(), event.getBlock().getType()));
                } else if(seaLevel.getChange() == SeaChange.DOWN) {
                    event.setCancelled(true);
                }
            }
        }
    }

    /**
     * Load the sea-level distribution model
     */
    @Override
    public void setJsonModel(JsonObject jsonModel) {
        super.setJsonModel(jsonModel);
        seaMap = GlobalWarming.getInstance().getGson().fromJson(
                jsonModel.get("distribution"),
                new TypeToken<Distribution>() {
                }.getType());


        if (seaMap == null) {
            unregister();
        } else {
            queueTicks = jsonModel.get("queue-ticks").getAsInt();
            startQueueLoader();
        }
    }
}
