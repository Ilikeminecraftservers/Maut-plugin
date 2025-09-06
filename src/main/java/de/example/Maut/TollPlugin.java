package de.example.maut;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class TollPlugin extends JavaPlugin implements Listener {
    private Economy econ;
    private final Set<UUID> allowedPlayers = ConcurrentHashMap.newKeySet();
    private final List<Location> gateLocations = new ArrayList<>();
    private final Map<Location, BlockData> originalBlockData = new HashMap<>();
    private Location closePlateLocation;
    private Material gateCloseFallback = Material.BARRIER;
    private int openDurationTicks = 20 * 15; // default 15s
    private BukkitTask autoCloseTask;
    private volatile boolean gateOpen = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!setupEconomy()) {
            getLogger().severe("Vault (Economy) nicht gefunden. Plugin deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("TollPlugin geladen.");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        econ = rsp.getProvider();
        return econ != null;
    }

    private void loadConfig() {
        gateLocations.clear();
        originalBlockData.clear();

        List<Map<?,?>> gateList = getConfig().getMapList("gate-blocks");
        for (Map<?,?> m : gateList) {
            try {
                String world = Objects.toString(m.get("world"), "world");
                int x = ((Number) m.get("x")).intValue();
                int y = ((Number) m.get("y")).intValue();
                int z = ((Number) m.get("z")).intValue();
                World w = Bukkit.getWorld(world);
                if (w == null) {
                    getLogger().warning("Welt nicht gefunden in gate-blocks: " + world);
                    continue;
                }
                gateLocations.add(new Location(w, x, y, z));
            } catch (Exception ex) {
                getLogger().warning("Fehler beim Lesen eines gate-block-Eintrags: " + ex.getMessage());
            }
        }

        ConfigurationSection cp = getConfig().getConfigurationSection("close-plate");
        if (cp != null) {
            try {
                String world = cp.getString("world", "world");
                int x = cp.getInt("x");
                int y = cp.getInt("y");
                int z = cp.getInt("z");
                World w = Bukkit.getWorld(world);
                if (w == null) {
                    getLogger().warning("Welt nicht gefunden für close-plate: " + world);
                } else {
                    closePlateLocation = new Location(w, x, y, z);
                }
            } catch (Exception ex) {
                getLogger().warning("Fehler beim Lesen der close-plate: " + ex.getMessage());
            }
        } else {
            getLogger().warning("close-plate nicht konfiguriert.");
        }

        String matName = getConfig().getString("gate-close-material", "BARRIER");
        Material m = Material.matchMaterial(matName);
        if (m != null) gateCloseFallback = m;

        int seconds = getConfig().getInt("open-duration-seconds", 15);
        openDurationTicks = Math.max(1, seconds) * 20;

        getLogger().info("Konfiguration geladen: " + gateLocations.size() + " Gate-Blöcke, close-plate=" + (closePlateLocation != null));
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_BLOCK) {
            Block clicked = event.getClickedBlock();
            if (clicked == null) return;
            BlockState state = clicked.getState();
            if (state instanceof Sign) {
                Sign sign = (Sign) state;
                String first = sign.getLine(0) == null ? "" : sign.getLine(0).trim();
                if (first.equalsIgnoreCase("[MAUT]") || first.equalsIgnoreCase("[TOLL]")) {
                    event.setCancelled(true);
                    handlePayment(event.getPlayer().getUniqueId(), event);
                }
            }
        } else if (action == Action.PHYSICAL) {
            Block clicked = event.getClickedBlock();
            if (clicked == null || closePlateLocation == null) return;
            if (isSameBlockLocation(clicked.getLocation(), closePlateLocation)) {
                UUID uid = event.getPlayer().getUniqueId();
                if (allowedPlayers.contains(uid)) {
                    closeGate();
                    allowedPlayers.remove(uid);
                    event.getPlayer().sendMessage("Schranke geschlossen.");
                }
            }
        }
    }

    private void handlePayment(UUID playerUuid, PlayerInteractEvent event) {
        org.bukkit.entity.Player player = event.getPlayer();
        double price = getConfig().getDouble("price", 5.0);
        double balance = econ.getBalance(player);
        if (balance < price) {
            player.sendMessage("Nicht genug Geld. Benötigt: " + price);
            return;
        }
        EconomyResponse r = econ.withdrawPlayer(player, price);
        if (!r.transactionSuccess) {
            player.sendMessage("Zahlung fehlgeschlagen: " + r.errorMessage);
            return;
        }

        allowedPlayers.add(playerUuid);
        openGate();
        player.sendMessage("Bezahlung erfolgreich. Schranke geöffnet.");
    }

    private synchronized void openGate() {
        if (gateOpen) return;
        originalBlockData.clear();
        for (Location loc : gateLocations) {
            try {
                Block b = loc.getBlock();
                originalBlockData.put(loc, b.getBlockData());
                b.setType(Material.AIR, false);
            } catch (Exception ex) {
                getLogger().warning("Konnte Gate-Block nicht zu AIR machen: " + ex.getMessage());
            }
        }
        gateOpen = true;

        if (autoCloseTask != null) autoCloseTask.cancel();
        autoCloseTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            synchronized (TollPlugin.this) {
                if (gateOpen) {
                    getLogger().info("Auto-close: Schließe Schranke nach Timeout.");
                    closeGate();
                    allowedPlayers.clear();
                }
            }
        }, openDurationTicks);

        getLogger().info("Schranke geöffnet.");
    }

    private synchronized void closeGate() {
        if (!gateOpen) return;
        for (Location loc : gateLocations) {
            try {
                Block b = loc.getBlock();
                BlockData data = originalBlockData.get(loc);
                if (data != null) {
                    b.setBlockData(data, false);
                } else {
                    b.setType(gateCloseFallback, false);
                }
            } catch (Exception ex) {
                getLogger().warning("Konnte Gate-Block nicht wiederherstellen: " + ex.getMessage());
            }
        }
        originalBlockData.clear();
        gateOpen = false;
        if (autoCloseTask != null) {
            autoCloseTask.cancel();
            autoCloseTask = null;
        }
        getLogger().info("Schranke geschlossen.");
    }

    private boolean isSameBlockLocation(Location a, Location b) {
        if (a == null || b == null) return false;
        if (!Objects.equals(a.getWorld(), b.getWorld())) return false;
        return a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }
          }
