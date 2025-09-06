// src/main/java/de/beispiel/maut/MautPlugin.java
package de.beispiel.maut;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class MautPlugin extends JavaPlugin implements Listener {

    private static Economy econ = null;
    // Map: regionName -> erlaubte Spieler
    private final Map<String, Set<UUID>> allowedPlayers = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!setupEconomy()) {
            getLogger().severe("Vault/Economy nicht gefunden!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("MautPlugin aktiviert.");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        econ = rsp.getProvider();
        return econ != null;
    }

    @EventHandler
    public void onSignClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!(e.getClickedBlock().getState() instanceof Sign sign)) return;

        if (!"[MAUT]".equalsIgnoreCase(sign.getLine(0))) return;

        String regionName = sign.getLine(1).trim();
        if (regionName.isEmpty()) {
            e.getPlayer().sendMessage(ChatColor.RED + "Region-Name fehlt auf dem Schild.");
            return;
        }

        double price;
        int duration;
        try {
            price = Double.parseDouble(sign.getLine(2).trim());
        } catch (Exception ex) {
            price = getConfig().getDouble("default-price", 5.0);
        }
        try {
            duration = Integer.parseInt(sign.getLine(3).trim());
        } catch (Exception ex) {
            duration = getConfig().getInt("default-duration-seconds", 15);
        }

        Player p = e.getPlayer();

        if (econ.getBalance(p) < price) {
            p.sendMessage(ChatColor.RED + "Nicht genug Geld für die Maut.");
            return;
        }

        econ.withdrawPlayer(p, price);
        p.sendMessage(ChatColor.GREEN + "Maut bezahlt für Region " + regionName +
                ". Zugang für " + duration + " Sekunden.");
        allowedPlayers.computeIfAbsent(regionName, k -> new HashSet<>()).add(p.getUniqueId());

        // Entfernen nach Ablauf
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Set<UUID> set = allowedPlayers.get(regionName);
            if (set != null) {
                set.remove(p.getUniqueId());
                p.sendMessage(ChatColor.YELLOW + "Deine Mautzeit für Region " + regionName + " ist abgelaufen.");
            }
        }, duration * 20L);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location to = e.getTo();
        if (to == null) return;

        List<String> regionsHere = getRegionsAt(to);
        if (regionsHere.isEmpty()) return;

        for (String r : regionsHere) {
            Set<UUID> set = allowedPlayers.get(r);
            if (set != null && set.contains(p.getUniqueId())) {
                return; // erlaubt in dieser Region
            }
        }

        // In mindestens einer Region ohne Erlaubnis
        p.sendMessage(ChatColor.RED + "Du hast für diese Region nicht bezahlt.");
        e.setCancelled(true);
    }

    private List<String> getRegionsAt(Location loc) {
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager regions = container.get(BukkitAdapter.adapt(loc.getWorld()));
        if (regions == null) return Collections.emptyList();

        ApplicableRegionSet set = regions.getApplicableRegions(BukkitAdapter.asBlockVector(loc));
        List<String> result = new ArrayList<>();
        for (ProtectedRegion r : set) {
            result.add(r.getId());
        }
        return result;
    }
            }
