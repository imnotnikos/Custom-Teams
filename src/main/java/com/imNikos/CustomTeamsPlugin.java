package com.imNikos;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemFlag;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import io.papermc.paper.scoreboard.numbers.NumberFormat;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class CustomTeamsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // ==========================================
    // Event Mode
    // ==========================================
    public enum EventMode { TEAMS, FFA }

    // ==========================================
    // Custom Team Enum (4 teams, "light" colors)
    // ==========================================
    // Display names stay RED/BLUE/GREEN/YELLOW even though the actual color used
    // everywhere (chat, nametag, tab list, wool marker, firework) is the lighter
    // shade. NamedTextColor.RED/AQUA/GREEN/YELLOW already ARE the light variants
    // in Minecraft's legacy color palette (§c/§b/§a/§e), so no custom RGB hacks
    // are needed for the scoreboard team color. RED's marker block is plain
    // RED_WOOL/RED_DYE (rather than pink) per admin preference; every other
    // team keeps its original marker material.
    public enum EventTeam {
        RED("RED", NamedTextColor.RED, Material.RED_WOOL, Material.RED_DYE, Color.fromRGB(255, 85, 85)),
        BLUE("BLUE", NamedTextColor.AQUA, Material.LIGHT_BLUE_WOOL, Material.LIGHT_BLUE_DYE, Color.fromRGB(85, 255, 255)),
        GREEN("GREEN", NamedTextColor.GREEN, Material.LIME_WOOL, Material.LIME_DYE, Color.fromRGB(85, 255, 85)),
        YELLOW("YELLOW", NamedTextColor.YELLOW, Material.YELLOW_WOOL, Material.YELLOW_DYE, Color.fromRGB(255, 255, 85));

        private final String displayName;
        private final NamedTextColor color;
        private final Material wool;
        private final Material resetDye;
        private final Color fireworkColor;

        EventTeam(String displayName, NamedTextColor color, Material wool, Material resetDye, Color fireworkColor) {
            this.displayName = displayName;
            this.color = color;
            this.wool = wool;
            this.resetDye = resetDye;
            this.fireworkColor = fireworkColor;
        }

        public String getDisplayName() { return displayName; }
        public NamedTextColor getScoreboardColor() { return color; }
        public Material getWool() { return wool; }
        public Material getResetDye() { return resetDye; }
        public Color getFireworkColor() { return fireworkColor; }

        public static EventTeam fromWool(Material material) {
            for (EventTeam team : values()) if (team.getWool() == material) return team;
            return null;
        }

        public static EventTeam fromResetDye(Material material) {
            for (EventTeam team : values()) if (team.getResetDye() == material) return team;
            return null;
        }
    }

    private FileConfiguration kitsDb;
    private File kitsFile;
    private boolean eventRunning = false;
    private boolean countdownActive = false;
    private boolean celebrationActive = false;
    private UUID countdownInitiator = null;
    private BukkitTask countdownTask = null;
    // The admin who clicked "Start" for the currently running (or last-run) event.
    // Unlike countdownInitiator (which is cleared once the countdown finishes),
    // this stays set for the whole match so every participant's kit can be
    // resolved against the host's kit selection.
    private UUID eventHostId = null;

    // Toggles
    private GameMode targetGameMode = GameMode.SURVIVAL;
    private boolean healthOnKill = true;
    private boolean naturalRegenEnabled = true;

    // New Game Options settings
    private int startCountdownSeconds = 5;          // 0 / 5 / 10
    private int inventoryManagementSeconds = 0;      // 0 / 5 / 10
    private int spawnInvincibilitySeconds = 5;       // 0 / 5 / 10 (grace period right after spawning in)
    private int celebrationSeconds = 10;             // 0 / 5 / 10
    private boolean healthIndicatorEnabled = false;  // health under nametag
    private boolean itemDropEnabled = true;          // item drops for participants
    private boolean dropItemsOnDeath = true;          // whether death drops actually land on the ground

    private Objective healthObjective;

    // Modes
    private EventMode queuedMode = null;   // what will run on next Start Event
    private EventMode currentMode = null;  // what is actively running

    // Tracking (pre-event restore state, shared by both modes)
    private final Map<UUID, ItemStack[]> preEventInventories = new HashMap<>();
    private final Map<UUID, GameMode> preEventGameModes = new HashMap<>();
    private final Map<UUID, Location> preEventLocations = new HashMap<>();
    private final Map<UUID, Location> deathLocations = new HashMap<>();

    private final Map<UUID, ItemStack[]> savedKitBuilderInventories = new HashMap<>();
    private final Map<UUID, GameMode> savedKitBuilderGameModes = new HashMap<>();
    private final Map<UUID, Integer> editingKitNumber = new HashMap<>();

    // Teams mode
    private final Map<UUID, EventTeam> playerTeams = new HashMap<>();
    private final Map<UUID, EventTeam> adminEditingTeam = new HashMap<>();

    // FFA mode
    private final Map<UUID, Set<UUID>> ffaSelections = new HashMap<>(); // in-progress selector state per admin
    private final Map<UUID, Integer> ffaSelectPage = new HashMap<>();
    private Set<UUID> ffaPendingPlayers = new HashSet<>(); // finalized roster for the next FFA start
    private final Set<UUID> ffaPlayers = new HashSet<>();  // active participants while FFA is running

    // Spectating / invincibility / freeze
    private final Set<UUID> spectatingPlayers = new HashSet<>();
    private final Set<UUID> invinciblePlayers = new HashSet<>();
    private final Set<UUID> frozenPlayers = new HashSet<>();

    // Anti-flag-kick landing lock: while a participant is falling to their spawn's
    // surface (feet not yet in contact with the ground), horizontal (X/Z) movement
    // is pinned to the spawn column so they can't be flagged as flying/drifting.
    // Removed automatically the instant Player#isOnGround() becomes true.
    private final Map<UUID, double[]> spawnLandingLock = new HashMap<>();

    private BukkitTask healthIndicatorTask = null;

    // Team spawn editing
    private final Set<UUID> teamSpawnEditMode = new HashSet<>();
    private final Map<UUID, Map<EventTeam, List<Location>>> teamSpawnBuffer = new HashMap<>();
    private final Map<UUID, GameMode> teamSpawnEditPrevGameMode = new HashMap<>();

    // FFA spawn editing
    private final Set<UUID> ffaSpawnEditMode = new HashSet<>();
    private final Map<UUID, String> ffaSpawnEditType = new HashMap<>(); // "EXACT" or "RADIUS"
    private final Map<UUID, List<Location>> ffaExactSpawnBuffer = new HashMap<>();
    private final Map<UUID, Integer> ffaExactSpawnNeeded = new HashMap<>();
    private final Map<UUID, GameMode> ffaSpawnEditPrevGameMode = new HashMap<>();

    private BossBar eventBossBar;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadKitsDatabase();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("ct").setExecutor(this);
        getCommand("ct").setTabCompleter(this);

        eventBossBar = Bukkit.createBossBar("Event Active", BarColor.RED, BarStyle.SOLID);

        targetGameMode = GameMode.valueOf(getConfig().getString("settings.gamemode-on-start", "SURVIVAL").toUpperCase());
        healthOnKill = getConfig().getBoolean("settings.health-on-kill", true);
        naturalRegenEnabled = getConfig().getBoolean("settings.natural-regen", true);
        startCountdownSeconds = getConfig().getInt("settings.start-countdown", 5);
        inventoryManagementSeconds = getConfig().getInt("settings.inventory-management-time", 0);
        spawnInvincibilitySeconds = getConfig().getInt("settings.spawn-invincibility", 5);
        celebrationSeconds = getConfig().getInt("settings.celebration-time", 10);
        healthIndicatorEnabled = getConfig().getBoolean("settings.health-indicator", false);
        itemDropEnabled = getConfig().getBoolean("settings.item-drop-enabled", true);
        dropItemsOnDeath = getConfig().getBoolean("settings.drop-items-on-death", true);

        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (EventTeam team : EventTeam.values()) {
            Team sTeam = board.getTeam("CT_" + team.name());
            if (sTeam == null) sTeam = board.registerNewTeam("CT_" + team.name());
            sTeam.color(team.getScoreboardColor());
            sTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
        }

        Objective existingObjective = board.getObjective("ct_health");
        healthObjective = existingObjective != null ? existingObjective
                : board.registerNewObjective("ct_health", Criteria.DUMMY, Component.empty());
        applyHealthIndicatorDisplay();

        getLogger().info("CustomTeams initialized successfully.");
    }

    @Override
    public void onDisable() {
        if (eventBossBar != null) eventBossBar.removeAll();
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (EventTeam team : EventTeam.values()) {
                Team sTeam = board.getTeam("CT_" + team.name());
                if (sTeam != null) sTeam.removeEntry(p.getName());
            }
            p.playerListName(null);
        }
    }

    private void loadKitsDatabase() {
        kitsFile = new File(getDataFolder(), "kits.yml");
        if (!kitsFile.exists()) {
            try { kitsFile.createNewFile(); } catch (IOException ignored) {}
        }
        kitsDb = YamlConfiguration.loadConfiguration(kitsFile);
    }

    private void saveKitsDatabase() {
        try { kitsDb.save(kitsFile); } catch (IOException ignored) {}
    }

    private void applyHealthIndicatorDisplay() {
        if (healthObjective == null) return;
        if (healthIndicatorEnabled && eventRunning) {
            healthObjective.setDisplaySlot(DisplaySlot.BELOW_NAME);
        } else {
            healthObjective.setDisplaySlot(null);
        }
    }

    /**
     * Runs only while an event is live. Every tick-and-a-half it (re)writes a
     * score for each active, non-spectating participant so only participating
     * players ever show a heart/health readout below their nametag; anyone
     * else (staff, spectators, players who left) simply has no score entry.
     */
    private void startHealthIndicatorTask() {
        stopHealthIndicatorTask();
        healthIndicatorTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!eventRunning) { cancel(); return; }
                if (!healthIndicatorEnabled || healthObjective == null) return;

                Set<UUID> participants = currentMode == EventMode.FFA ? ffaPlayers : playerTeams.keySet();
                for (UUID uuid : participants) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p == null || !p.isOnline()) continue;
                    if (spectatingPlayers.contains(uuid)) {
                        healthObjective.getScoreboard().resetScores(p);
                        continue;
                    }
                    int hp = (int) Math.ceil(p.getHealth());
                    var score = healthObjective.getScore(p.getName());
                    score.setScore(hp);
                    score.numberFormat(NumberFormat.fixed(Component.text("\u2665 " + hp).color(NamedTextColor.RED)));
                }
            }
        }.runTaskTimer(this, 0L, 10L);
    }

    private void stopHealthIndicatorTask() {
        if (healthIndicatorTask != null) {
            healthIndicatorTask.cancel();
            healthIndicatorTask = null;
        }
    }

    private int cycleTime(int current) {
        if (current <= 0) return 5;
        if (current == 5) return 10;
        return 0;
    }

    // ==========================================
    // Commands
    // ==========================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        if (args.length == 0) {
            if (player.hasPermission("customteams.admin")) {
                openMainGUI(player);
            } else {
                player.sendMessage(Component.text("You do not have permission to manage events.").color(NamedTextColor.RED));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("leave")) {
            handleLeaveEvent(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("kits")) {
            openPlayerKitChoiceGUI(player);
            return true;
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("leave");
            completions.add("kits");
            List<String> result = new ArrayList<>();
            for (String c : completions) {
                if (c.toLowerCase().startsWith(args[0].toLowerCase())) result.add(c);
            }
            return result;
        }
        return new ArrayList<>();
    }

    // ==========================================
    // Event Flow
    // ==========================================

    private void beginStartCountdown(Player admin) {
        if (countdownActive) return;
        if (queuedMode == null) {
            admin.sendMessage(Component.text("No FFA participants or team assignments have been set up yet.").color(NamedTextColor.RED));
            return;
        }

        if (startCountdownSeconds <= 0) {
            startEvent(admin);
            return;
        }

        countdownActive = true;
        countdownInitiator = admin.getUniqueId();

        countdownTask = new BukkitRunnable() {
            int count = startCountdownSeconds;
            @Override
            public void run() {
                if (count > 0) {
                    Component title = Component.text(count).color(NamedTextColor.RED).decorate(TextDecoration.BOLD);
                    Component subtitle = Component.text("Event starting...").color(NamedTextColor.GOLD);
                    for (UUID id : upcomingParticipants()) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null) p.showTitle(Title.title(title, subtitle));
                    }
                    count--;
                } else {
                    startEvent(admin);
                    countdownActive = false;
                    countdownInitiator = null;
                    this.cancel();
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private Set<UUID> upcomingParticipants() {
        if (queuedMode == EventMode.FFA) return ffaPendingPlayers;
        return playerTeams.keySet();
    }

    private void startEvent(Player admin) {
        if (queuedMode == null) {
            admin.sendMessage(Component.text("No FFA participants or team assignments have been set up yet.").color(NamedTextColor.RED));
            return;
        }

        eventRunning = true;
        currentMode = queuedMode;
        celebrationActive = false;
        eventHostId = admin.getUniqueId();
        applyHealthIndicatorDisplay();
        startHealthIndicatorTask();
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        List<Player> startedPlayers = new ArrayList<>();

        if (currentMode == EventMode.FFA) {
            List<Location> spawns = resolveFfaSpawns(ffaPendingPlayers.size());
            if (spawns.isEmpty()) {
                admin.sendMessage(Component.text("Warning: No FFA spawns configured!").color(NamedTextColor.RED));
            }
            int idx = 0;
            for (UUID uuid : new ArrayList<>(ffaPendingPlayers)) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) continue;
                ffaPlayers.add(uuid);

                preEventInventories.put(uuid, p.getInventory().getContents());
                preEventGameModes.put(uuid, p.getGameMode());
                preEventLocations.put(uuid, p.getLocation());

                if (idx < spawns.size()) {
                    Location spawnLoc = spawns.get(idx);
                    p.teleport(spawnLoc);
                    spawnLandingLock.put(uuid, new double[]{spawnLoc.getX(), spawnLoc.getZ()});
                }
                idx++;

                setupCommonMatchState(p);
                p.playerListName(Component.text(p.getName()).color(NamedTextColor.WHITE));
                giveKit(p);
                eventBossBar.addPlayer(p);
                startedPlayers.add(p);
            }
        } else {
            Map<EventTeam, List<Player>> rosterByTeam = new EnumMap<>(EventTeam.class);
            for (Player p : Bukkit.getOnlinePlayers()) {
                EventTeam team = playerTeams.get(p.getUniqueId());
                if (team != null) rosterByTeam.computeIfAbsent(team, k -> new ArrayList<>()).add(p);
            }

            Map<UUID, Location> teamSpawnAssignments = new HashMap<>();
            for (Map.Entry<EventTeam, List<Player>> entry : rosterByTeam.entrySet()) {
                List<Location> spawns = getTeamSpawnPoints(entry.getKey());
                if (spawns.isEmpty()) {
                    admin.sendMessage(Component.text("Warning: No spawn points set for " + entry.getKey().getDisplayName()).color(NamedTextColor.RED));
                    continue;
                }
                teamSpawnAssignments.putAll(assignTeamSpawns(entry.getValue(), spawns));
            }

            for (Player p : Bukkit.getOnlinePlayers()) {
                EventTeam team = playerTeams.get(p.getUniqueId());
                if (team == null) continue;

                preEventInventories.put(p.getUniqueId(), p.getInventory().getContents());
                preEventGameModes.put(p.getUniqueId(), p.getGameMode());
                preEventLocations.put(p.getUniqueId(), p.getLocation());

                Location spawn = teamSpawnAssignments.get(p.getUniqueId());
                if (spawn != null) {
                    p.teleport(spawn);
                    spawnLandingLock.put(p.getUniqueId(), new double[]{spawn.getX(), spawn.getZ()});
                }

                setupCommonMatchState(p);

                Team sTeam = board.getTeam("CT_" + team.name());
                if (sTeam != null) sTeam.addEntry(p.getName());
                p.playerListName(Component.text(p.getName()).color(team.getScoreboardColor()));

                giveKit(p);
                eventBossBar.addPlayer(p);
                startedPlayers.add(p);
            }
        }

        beginMatchSequence(startedPlayers);
        updateGameState();
    }

    /**
     * Handles the transition from "kit given" to "match live". If an inventory
     * management window is configured, participants are frozen in place (but
     * fully invincible) so they can rearrange their kit before the match
     * actually begins; otherwise the match starts immediately. Either way, the
     * post-spawn invincibility/no-damage grace period uses the configurable
     * spawn-invincibility timer (0/5/10s), same as the other event timers.
     */
    private void beginMatchSequence(List<Player> participants) {
        if (inventoryManagementSeconds > 0) {
            for (Player p : participants) {
                UUID id = p.getUniqueId();
                frozenPlayers.add(id);
                invinciblePlayers.add(id);
                p.showTitle(Title.title(
                        Component.text("Manage Your Kit!").color(NamedTextColor.AQUA).decorate(TextDecoration.BOLD),
                        Component.text("The match begins in " + inventoryManagementSeconds + " seconds...").color(NamedTextColor.GRAY)));
            }

            Bukkit.getScheduler().runTaskLater(this, () -> {
                for (Player p : participants) {
                    UUID id = p.getUniqueId();
                    frozenPlayers.remove(id);
                    invinciblePlayers.remove(id);
                    p.sendMessage(Component.text("\n\n★ THE EVENT HAS STARTED! ★\n\n").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
                    p.showTitle(Title.title(
                            Component.text("FIGHT!").color(NamedTextColor.RED).decorate(TextDecoration.BOLD),
                            Component.empty()));
                    grantInvincibility(p, spawnInvincibilitySeconds);
                }
            }, inventoryManagementSeconds * 20L);
        } else {
            for (Player p : participants) {
                grantInvincibility(p, spawnInvincibilitySeconds);
                p.sendMessage(Component.text("\n\n★ THE EVENT HAS STARTED! ★\n\n").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
            }
        }
    }

    private void setupCommonMatchState(Player p) {
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setSaturation(10.0f);
        p.clearActivePotionEffects();
        p.setGameMode(targetGameMode);
        // Explicitly drop any leftover flight state from Creative (kit editor / spawn
        // editor) so survival/adventure players never re-enter with allowFlight still
        // true - a common cause of anti-cheat "flying" kicks right after a teleport.
        p.setAllowFlight(false);
        p.setFlying(false);
        p.setFallDistance(0f);
        p.setVelocity(p.getVelocity().zero());
        spectatingPlayers.remove(p.getUniqueId());
        deathLocations.remove(p.getUniqueId());
    }

    // Natural regen is enforced per-participant via the EntityRegainHealthEvent
    // handler below rather than a world gamerule, so it never affects players
    // who aren't currently in the event.

    private void endEvent() {
        eventRunning = false;
        celebrationActive = false;
        eventBossBar.removeAll();

        Set<UUID> participants = currentMode == EventMode.FFA ? new HashSet<>(ffaPlayers) : new HashSet<>(playerTeams.keySet());
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (UUID uuid : participants) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.clearTitle();
                restorePlayerToPreEventState(p);
                board.resetScores(p);
            }
            spawnLandingLock.remove(uuid);
        }

        stopHealthIndicatorTask();
        applyHealthIndicatorDisplay();

        preEventInventories.clear();
        preEventGameModes.clear();
        preEventLocations.clear();
        deathLocations.clear();
        spectatingPlayers.clear();
        invinciblePlayers.clear();
        frozenPlayers.clear();
        ffaPlayers.clear();
        currentMode = null;
        eventHostId = null;
    }

    private void handleLeaveEvent(Player player) {
        UUID id = player.getUniqueId();
        boolean inMatch = eventRunning && isParticipant(id);
        if (!inMatch) {
            player.sendMessage(Component.text("You are not currently in an active event.").color(NamedTextColor.RED));
            return;
        }

        restorePlayerToPreEventState(player);
        if (currentMode == EventMode.FFA) {
            ffaPlayers.remove(id);
        } else {
            playerTeams.remove(id);
        }
        spectatingPlayers.remove(id);
        deathLocations.remove(id);
        invinciblePlayers.remove(id);
        frozenPlayers.remove(id);
        spawnLandingLock.remove(id);
        if (healthObjective != null) Bukkit.getScoreboardManager().getMainScoreboard().resetScores(player);
        eventBossBar.removePlayer(player);
        updateGameState();

        player.sendMessage(Component.text("You have left the event.").color(NamedTextColor.YELLOW));
    }

    private void restorePlayerToPreEventState(Player p) {
        UUID id = p.getUniqueId();
        if (currentMode == EventMode.TEAMS) {
            EventTeam team = playerTeams.get(id);
            if (team != null) {
                Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
                Team sTeam = board.getTeam("CT_" + team.name());
                if (sTeam != null) sTeam.removeEntry(p.getName());
            }
        }
        p.playerListName(null);

        if (preEventInventories.containsKey(id)) {
            p.getInventory().setContents(preEventInventories.get(id));
            p.setGameMode(preEventGameModes.getOrDefault(id, GameMode.SURVIVAL));
            p.clearActivePotionEffects();

            Location preLoc = preEventLocations.get(id);
            if (preLoc != null) p.teleport(preLoc);
        }
    }

    private boolean isParticipant(UUID id) {
        if (!eventRunning) return false;
        return currentMode == EventMode.FFA ? ffaPlayers.contains(id) : playerTeams.containsKey(id);
    }

    private NamedTextColor getParticipantColor(UUID id) {
        if (currentMode == EventMode.FFA) return NamedTextColor.WHITE;
        EventTeam t = playerTeams.get(id);
        return t != null ? t.getScoreboardColor() : null;
    }

    // ==========================================
    // Logic: Kills & Game State Tracking
    // ==========================================

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        if (!eventRunning || celebrationActive) return;
        Player victim = e.getEntity();
        if (!isParticipant(victim.getUniqueId())) return;

        deathLocations.put(victim.getUniqueId(), victim.getLocation());
        spawnLandingLock.remove(victim.getUniqueId());

        if (!dropItemsOnDeath) {
            e.getDrops().clear();
        }

        Player killer = victim.getKiller();
        if (killer != null && isParticipant(killer.getUniqueId())) {
            if (healthOnKill) {
                killer.setHealth(20.0);
                killer.setFoodLevel(20);
                killer.setSaturation(10.0f);
            }
        }

        Bukkit.getScheduler().runTask(this, () -> victim.spigot().respawn());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent e) {
        if (!eventRunning) return;
        Player player = e.getPlayer();
        if (isParticipant(player.getUniqueId())) {
            spectatingPlayers.add(player.getUniqueId());

            if (deathLocations.containsKey(player.getUniqueId())) {
                e.setRespawnLocation(deathLocations.remove(player.getUniqueId()));
            }

            Bukkit.getScheduler().runTask(this, () -> {
                player.setGameMode(GameMode.SPECTATOR);
                player.sendMessage(Component.text("You died! You are now spectating. Type /ct leave to exit.").color(NamedTextColor.YELLOW));
                updateGameState();
            });
        }
    }

    private void updateGameState() {
        if (!eventRunning || celebrationActive) return;

        if (currentMode == EventMode.FFA) {
            List<UUID> alive = new ArrayList<>();
            for (UUID uuid : ffaPlayers) {
                if (!spectatingPlayers.contains(uuid)) alive.add(uuid);
            }
            eventBossBar.setTitle("Event Active - Players Left: " + alive.size());

            if (alive.size() <= 1) {
                celebrationActive = true;
                Player winner = alive.size() == 1 ? Bukkit.getPlayer(alive.get(0)) : null;

                Component title;
                Set<UUID> winnerIds = new HashSet<>();
                if (winner != null) {
                    title = Component.text(winner.getName() + " Won!").color(NamedTextColor.WHITE).decorate(TextDecoration.BOLD);
                    winnerIds.add(winner.getUniqueId());
                } else {
                    title = Component.text("Event Ended in a Draw!").color(NamedTextColor.GRAY).decorate(TextDecoration.BOLD);
                }

                for (UUID uuid : ffaPlayers) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) p.showTitle(Title.title(title, Component.empty()));
                }

                startCelebration(winnerIds, null, new HashSet<>(ffaPlayers));
            }
        } else {
            Set<EventTeam> aliveTeams = new HashSet<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (playerTeams.containsKey(p.getUniqueId()) && !spectatingPlayers.contains(p.getUniqueId())) {
                    aliveTeams.add(playerTeams.get(p.getUniqueId()));
                }
            }

            int teamsLeft = aliveTeams.size();
            eventBossBar.setTitle("Event Active - Teams Left: " + teamsLeft);

            if (teamsLeft <= 1) {
                celebrationActive = true;
                EventTeam winnerTeam = (teamsLeft == 1) ? aliveTeams.iterator().next() : null;

                Component title;
                if (winnerTeam != null) {
                    title = Component.text("Team " + winnerTeam.getDisplayName() + " Won!").color(winnerTeam.getScoreboardColor()).decorate(TextDecoration.BOLD);
                } else {
                    title = Component.text("Event Ended in a Draw!").color(NamedTextColor.GRAY).decorate(TextDecoration.BOLD);
                }

                Set<UUID> winnerIds = new HashSet<>();
                for (Map.Entry<UUID, EventTeam> entry : playerTeams.entrySet()) {
                    if (winnerTeam != null && entry.getValue() == winnerTeam) winnerIds.add(entry.getKey());
                }

                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (playerTeams.containsKey(p.getUniqueId())) {
                        p.showTitle(Title.title(title, Component.empty()));
                    }
                }

                startCelebration(winnerIds, winnerTeam, playerTeams.keySet());
            }
        }
    }

    /**
     * Runs the victory celebration for the configured celebration-time window.
     * Winners (or everyone, on a draw) are made invincible and unable to
     * damage anything for its duration, and fireworks spawn on the winning
     * side each second until the event ends. A celebration time of 0 skips
     * the fireworks entirely and ends the event immediately.
     */
    private void startCelebration(Set<UUID> winnerIds, EventTeam winnerTeamColor, Set<UUID> allParticipantsSnapshot) {
        Set<UUID> fireworkTargets = winnerIds.isEmpty() ? new HashSet<>(allParticipantsSnapshot) : winnerIds;
        int totalTicks = celebrationSeconds * 20;

        if (totalTicks <= 0) {
            endEvent();
            return;
        }

        for (UUID uuid : fireworkTargets) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) grantInvincibility(p, celebrationSeconds);
        }

        new BukkitRunnable() {
            int ticksElapsed = 0;
            @Override
            public void run() {
                if (!eventRunning) { cancel(); return; }

                if (ticksElapsed < totalTicks) {
                    for (UUID uuid : fireworkTargets) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null) spawnVictoryFirework(p.getLocation().add(0, 1, 0), winnerTeamColor);
                    }
                    ticksElapsed += 20;
                } else {
                    endEvent();
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void spawnVictoryFirework(Location loc, EventTeam team) {
        Firework fw = loc.getWorld().spawn(loc, Firework.class);
        FireworkMeta fm = fw.getFireworkMeta();

        Color bukkitColor = team != null ? team.getFireworkColor() : Color.WHITE;

        fm.addEffect(FireworkEffect.builder().flicker(true).trail(true)
                .with(FireworkEffect.Type.BALL_LARGE).withColor(bukkitColor).withFade(Color.WHITE).build());
        fm.setPower(1);
        fw.setFireworkMeta(fm);
    }

    // ==========================================
    // Invincibility (join grace period + victory window)
    // ==========================================

    private void grantInvincibility(Player p, int seconds) {
        if (seconds <= 0) return;
        UUID id = p.getUniqueId();
        invinciblePlayers.add(id);
        Bukkit.getScheduler().runTaskLater(this, () -> invinciblePlayers.remove(id), seconds * 20L);
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && invinciblePlayers.contains(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && invinciblePlayers.contains(p.getUniqueId())) {
            // Protected players (loading in or celebrating a win) cannot deal damage either.
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityRegainHealth(EntityRegainHealthEvent e) {
        // Only affects players who are currently in the event, and only the
        // natural (food-driven) regen — other heal sources (potions, kits,
        // health-on-kill, etc.) are untouched.
        if (naturalRegenEnabled) return;
        if (e.getRegainReason() != EntityRegainHealthEvent.RegainReason.SATIATED) return;
        if (e.getEntity() instanceof Player p && isParticipant(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    /**
     * Keeps a player pinned in place (during the inventory-management window,
     * or right after spawning in) WITHOUT ever holding them above the ground.
     * While the player is not yet on solid ground, only X/Z drift is blocked -
     * gravity is left completely alone so they fall naturally to the surface.
     * Only once Player#isOnGround() is true do we also lock Y (for frozen
     * players) or release the lock entirely (for players just settling into
     * their spawn). This is what prevents anti-cheat "flying" kicks: the
     * player is never held motionless while airborne.
     */
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        Location to = e.getTo();
        if (to == null) return;

        boolean settlingAfterSpawn = spawnLandingLock.containsKey(id);
        boolean frozen = frozenPlayers.contains(id);
        if (!settlingAfterSpawn && !frozen) return;

        if (!p.isOnGround()) {
            // Still airborne (falling to the ground): never restrict Y, only
            // keep them from drifting sideways while gravity does its job.
            double[] lock = spawnLandingLock.computeIfAbsent(id,
                    k -> new double[]{p.getLocation().getX(), p.getLocation().getZ()});
            if (to.getX() != lock[0] || to.getZ() != lock[1]) {
                Location corrected = to.clone();
                corrected.setX(lock[0]);
                corrected.setZ(lock[1]);
                e.setTo(corrected);
            }
            return;
        }

        // Feet are now confirmed touching solid ground.
        spawnLandingLock.remove(id);

        if (frozen) {
            Location from = e.getFrom();
            if (from.getWorld() == null) return;
            if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                Location locked = from.clone();
                locked.setYaw(to.getYaw());
                locked.setPitch(to.getPitch());
                e.setTo(locked);
            }
        }
    }

    // ==========================================
    // GUIs
    // ==========================================

    private void openMainGUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("Custom Teams Settings").color(NamedTextColor.DARK_GRAY));

        inv.setItem(10, createGuiItem(Material.LEATHER_CHESTPLATE, Component.text("Teams").color(NamedTextColor.AQUA), "Manage event teams."));
        inv.setItem(12, createGuiItem(Material.COMPASS, Component.text("Spawns").color(NamedTextColor.LIGHT_PURPLE), "Set spawn points."));
        inv.setItem(14, createGuiItem(Material.DIAMOND_SWORD, Component.text("My Kits").color(NamedTextColor.GOLD), "Build and choose your own 3 kits.", "(Same as /ct kits. Only the event", "host's kit is actually used in-match.)"));
        inv.setItem(16, createGuiItem(Material.COMMAND_BLOCK, Component.text("Game Options").color(NamedTextColor.YELLOW), "Gamemode, timers, and toggles."));

        if (eventRunning) {
            inv.setItem(22, createGuiItem(Material.RED_WOOL, Component.text("Force End Event").color(NamedTextColor.RED).decorate(TextDecoration.BOLD)));
        } else if (countdownActive) {
            inv.setItem(22, createGuiItem(Material.ORANGE_WOOL, Component.text("Cancel Start").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD)));
        } else {
            String modeLabel = queuedMode == null ? "None" : queuedMode.name();
            inv.setItem(22, createGuiItem(Material.GREEN_WOOL, Component.text("Start Event").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD), "Queued mode: " + modeLabel));
        }

        player.openInventory(inv);
    }

    private void openGameOptionsGUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("Game Options").color(NamedTextColor.DARK_GRAY));

        Material gmMat = targetGameMode == GameMode.SURVIVAL ? Material.IRON_PICKAXE : Material.MAP;
        inv.setItem(10, createGuiItem(gmMat, Component.text("Gamemode: " + targetGameMode.name()).color(NamedTextColor.YELLOW), "Click to toggle Survival/Adventure."));

        Material regenMat = naturalRegenEnabled ? Material.GOLDEN_CARROT : Material.ROTTEN_FLESH;
        NamedTextColor regenColor = naturalRegenEnabled ? NamedTextColor.GREEN : NamedTextColor.RED;
        inv.setItem(11, createGuiItem(regenMat, Component.text("Natural Regen: " + (naturalRegenEnabled ? "ON" : "OFF")).color(regenColor), "Click to toggle natural regen for event participants only."));

        Material hokMat = healthOnKill ? Material.GOLDEN_APPLE : Material.APPLE;
        NamedTextColor hokColor = healthOnKill ? NamedTextColor.GREEN : NamedTextColor.RED;
        inv.setItem(12, createGuiItem(hokMat, Component.text("Health on Kill: " + (healthOnKill ? "ON" : "OFF")).color(hokColor), "Click to toggle."));

        inv.setItem(14, createGuiItem(Material.CLOCK, Component.text("Start Countdown: " + startCountdownSeconds + "s").color(NamedTextColor.AQUA), "Click to cycle: 0 / 5 / 10 seconds."));
        inv.setItem(15, createGuiItem(Material.ENDER_CHEST, Component.text("Inventory Management: " + inventoryManagementSeconds + "s").color(NamedTextColor.LIGHT_PURPLE), "Freeze players to manage their kit", "before the match truly begins.", "Click to cycle: 0 / 5 / 10 seconds."));
        inv.setItem(16, createGuiItem(Material.FIREWORK_ROCKET, Component.text("Celebration Time: " + celebrationSeconds + "s").color(NamedTextColor.GOLD), "Click to cycle: 0 / 5 / 10 seconds."));

        Material healthMat = healthIndicatorEnabled ? Material.BEETROOT : Material.GRAY_DYE;
        NamedTextColor healthColor = healthIndicatorEnabled ? NamedTextColor.GREEN : NamedTextColor.RED;
        inv.setItem(19, createGuiItem(healthMat, Component.text("Health Indicator: " + (healthIndicatorEnabled ? "ON" : "OFF")).color(healthColor), "Shows health under nametags.", "Click to toggle."));

        Material dropMat = itemDropEnabled ? Material.HOPPER : Material.BARRIER;
        NamedTextColor dropColor = itemDropEnabled ? NamedTextColor.GREEN : NamedTextColor.RED;
        inv.setItem(20, createGuiItem(dropMat, Component.text("Item Drops: " + (itemDropEnabled ? "ON" : "OFF")).color(dropColor), "Click to toggle item dropping", "for event participants."));

        Material deathDropMat = dropItemsOnDeath ? Material.CHEST : Material.BARRIER;
        NamedTextColor deathDropColor = dropItemsOnDeath ? NamedTextColor.GREEN : NamedTextColor.RED;
        inv.setItem(22, createGuiItem(deathDropMat, Component.text("Death Drops: " + (dropItemsOnDeath ? "ON" : "OFF")).color(deathDropColor),
                "Click to toggle whether a participant's", "items land on the ground when they die.",
                "(They still lose the items either way -", "this only controls if they're dropped.)"));

        inv.setItem(21, createGuiItem(Material.SHIELD, Component.text("Spawn Invincibility: " + spawnInvincibilitySeconds + "s").color(NamedTextColor.AQUA), "Grace period after spawning in", "(and after kit management, if any)", "where players can't take or deal damage.", "Click to cycle: 0 / 5 / 10 seconds."));

        inv.setItem(26, createGuiItem(Material.ARROW, Component.text("Back").color(NamedTextColor.GRAY)));
        player.openInventory(inv);
    }

    private void openTeamsMenuGUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("Team Management").color(NamedTextColor.DARK_GRAY));

        EventMode displayMode = queuedMode != null ? queuedMode : EventMode.FFA;
        Material modeMat = displayMode == EventMode.FFA ? Material.IRON_SWORD : Material.SHIELD;
        inv.setItem(4, createGuiItem(modeMat, Component.text("Game Type: " + displayMode.name()).color(NamedTextColor.YELLOW).decorate(TextDecoration.BOLD),
                "The mode that will run on the next Start Event.", "Click to switch between FFA and Teams."));

        inv.setItem(11, createGuiItem(Material.IRON_SWORD, Component.text("Free-For-All (FFA)").color(NamedTextColor.RED), "Select players and each fights solo."));
        inv.setItem(14, createGuiItem(Material.WHITE_WOOL, Component.text("Manual Teams").color(NamedTextColor.GREEN), "Select a color and assign players manually."));
        inv.setItem(15, createGuiItem(Material.NETHER_STAR, Component.text("Autofill Teams").color(NamedTextColor.LIGHT_PURPLE),
                "Randomly spreads all online players", "evenly across the 4 teams.", "Shuffles differently every time."));
        inv.setItem(22, createGuiItem(Material.ARROW, Component.text("Back").color(NamedTextColor.GRAY)));
        player.openInventory(inv);
    }

    /**
     * Randomly (and differently each time) spreads every online player across
     * the 4 team colors as evenly as possible, replacing any existing manual
     * team assignments.
     */
    private void autofillTeams(Player admin) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        Collections.shuffle(online);

        playerTeams.clear();
        EventTeam[] teams = EventTeam.values();
        for (int i = 0; i < online.size(); i++) {
            playerTeams.put(online.get(i).getUniqueId(), teams[i % teams.length]);
        }

        queuedMode = EventMode.TEAMS;

        for (Player p : online) {
            EventTeam team = playerTeams.get(p.getUniqueId());
            p.sendMessage(Component.text("You were autofilled onto team: ").color(NamedTextColor.GRAY)
                    .append(Component.text(team.getDisplayName()).color(team.getScoreboardColor()).decorate(TextDecoration.BOLD)));
        }
        admin.sendMessage(Component.text("Teams autofilled with " + online.size() + " players!").color(NamedTextColor.GREEN));
    }

    private void openFFASelectGUI(Player player, int page) {
        ffaSelectPage.put(player.getUniqueId(), page);
        Inventory inv = Bukkit.createInventory(null, 54, Component.text("FFA Participant Selector").color(NamedTextColor.DARK_GRAY));
        Set<UUID> selected = ffaSelections.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        int perPage = 45;
        int start = page * perPage;

        int slot = 0;
        for (int i = start; i < online.size() && slot < perPage; i++, slot++) {
            Player p = online.get(i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                meta.setOwningPlayer(p);
                meta.displayName(Component.text(p.getName()).color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));

                if (selected.contains(p.getUniqueId())) {
                    meta.lore(List.of(Component.text("SELECTED").color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)));
                    meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                } else {
                    meta.lore(List.of(Component.text("Click to include in FFA").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
                }
                head.setItemMeta(meta);
            }
            inv.setItem(slot, head);
        }

        inv.setItem(45, createGuiItem(Material.BARRIER, Component.text("Clear Selection").color(NamedTextColor.RED)));
        if (page > 0) inv.setItem(46, createGuiItem(Material.ARROW, Component.text("Previous Page").color(NamedTextColor.GRAY)));
        inv.setItem(49, createGuiItem(Material.OAK_DOOR, Component.text("Back").color(NamedTextColor.GRAY)));
        if (start + perPage < online.size()) inv.setItem(52, createGuiItem(Material.ARROW, Component.text("Next Page").color(NamedTextColor.GRAY)));
        inv.setItem(53, createGuiItem(Material.EMERALD_BLOCK, Component.text("Confirm & Set FFA Roster").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD), "Selected so far: " + selected.size()));

        player.openInventory(inv);
    }

    private void openColorSelectGUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("Select a Team Color").color(NamedTextColor.DARK_GRAY));
        EventTeam[] teams = EventTeam.values();
        for (int i = 0; i < teams.length; i++) {
            inv.setItem(i, createGuiItem(teams[i].getWool(), Component.text(teams[i].getDisplayName()).color(teams[i].getScoreboardColor())));
        }
        inv.setItem(22, createGuiItem(Material.ARROW, Component.text("Back").color(NamedTextColor.GRAY)));
        player.openInventory(inv);
    }

    private void openPlayerAssignGUI(Player player, EventTeam teamColor) {
        adminEditingTeam.put(player.getUniqueId(), teamColor);
        Inventory inv = Bukkit.createInventory(null, 54, Component.text("Assign: ").color(NamedTextColor.DARK_GRAY).append(Component.text(teamColor.getDisplayName()).color(teamColor.getScoreboardColor())));

        int slot = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (slot >= 45) break;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                meta.setOwningPlayer(p);
                meta.displayName(Component.text(p.getName()).color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                if (playerTeams.get(p.getUniqueId()) == teamColor) {
                    meta.lore(List.of(Component.text("Currently on this team!").color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)));
                    meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                } else {
                    EventTeam current = playerTeams.get(p.getUniqueId());
                    meta.lore(List.of(Component.text("Current Team: " + (current == null ? "None" : current.getDisplayName())).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
                }
                head.setItemMeta(meta);
            }
            inv.setItem(slot++, head);
        }
        inv.setItem(53, createGuiItem(Material.ARROW, Component.text("Back").color(NamedTextColor.GRAY)));
        player.openInventory(inv);
    }

    // ---------- Kit Creator (each player builds their own 3 kits) ----------

    private void openMasterKitEditorGUI(Player player, int kitNumber) {
        if (player.getGameMode() != GameMode.CREATIVE) {
            savedKitBuilderInventories.put(player.getUniqueId(), player.getInventory().getContents());
            savedKitBuilderGameModes.put(player.getUniqueId(), player.getGameMode());
            player.getInventory().clear();
            player.setGameMode(GameMode.CREATIVE);
        }
        editingKitNumber.put(player.getUniqueId(), kitNumber);

        Inventory inv = Bukkit.createInventory(null, 54, Component.text("Master Kit Creator - Kit " + kitNumber).color(NamedTextColor.DARK_GRAY));

        @SuppressWarnings("unchecked")
        List<ItemStack> existingKit = (List<ItemStack>) kitsDb.getList("playerkits." + player.getUniqueId() + ".kit" + kitNumber);
        if (existingKit != null && existingKit.size() == 54) {
            for (int i = 0; i < 54; i++) {
                if (existingKit.get(i) != null) inv.setItem(i, existingKit.get(i));
            }
        }

        if (inv.getItem(0) == null) inv.setItem(0, createGuiItem(Material.RED_STAINED_GLASS_PANE, Component.text("Helmet Slot").color(NamedTextColor.RED)));
        if (inv.getItem(1) == null) inv.setItem(1, createGuiItem(Material.RED_STAINED_GLASS_PANE, Component.text("Chestplate Slot").color(NamedTextColor.RED)));
        if (inv.getItem(2) == null) inv.setItem(2, createGuiItem(Material.RED_STAINED_GLASS_PANE, Component.text("Leggings Slot").color(NamedTextColor.RED)));
        if (inv.getItem(3) == null) inv.setItem(3, createGuiItem(Material.RED_STAINED_GLASS_PANE, Component.text("Boots Slot").color(NamedTextColor.RED)));
        if (inv.getItem(4) == null) inv.setItem(4, createGuiItem(Material.BLUE_STAINED_GLASS_PANE, Component.text("Offhand Slot").color(NamedTextColor.BLUE)));

        for (int i = 5; i <= 16; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, createGuiItem(Material.MAGENTA_STAINED_GLASS_PANE, Component.text("Potion Effect").color(NamedTextColor.LIGHT_PURPLE)));
        }

        inv.setItem(17, createGuiItem(Material.EMERALD_BLOCK, Component.text("SAVE KIT " + kitNumber).color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD)));

        player.openInventory(inv);
        player.sendMessage(Component.text("Editing Kit " + kitNumber + ". Place items in the slots. Click a stained glass pane with an item to swap it in. Click Emerald Block to save!").color(NamedTextColor.AQUA));
    }

    private void saveKitFromGUI(Player player, Inventory gui, int kitNumber) {
        List<ItemStack> kitToSave = new ArrayList<>(Arrays.asList(gui.getContents()));
        for (int i = 0; i < 18; i++) {
            ItemStack item = kitToSave.get(i);
            if (item != null && (item.getType().name().contains("GLASS_PANE") || item.getType() == Material.EMERALD_BLOCK)) {
                kitToSave.set(i, null);
            }
        }
        kitsDb.set("playerkits." + player.getUniqueId() + ".kit" + kitNumber, kitToSave);
        saveKitsDatabase();
        player.sendMessage(Component.text("Kit " + kitNumber + " saved!").color(NamedTextColor.GREEN));
    }

    // ---------- Player kits (each player builds and chooses among their own 3 kits) ----------

    private void openPlayerKitChoiceGUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("My Kits").color(NamedTextColor.DARK_GRAY));
        int choice = kitsDb.getInt("playerkits." + player.getUniqueId() + ".choice", 1);

        ItemStack kit1 = createGuiItem(Material.DIAMOND_CHESTPLATE, Component.text("Kit 1").color(NamedTextColor.AQUA),
                choice == 1 ? "SELECTED" : "Left-click to select this kit.", "Right-click to edit this kit.");
        ItemStack kit2 = createGuiItem(Material.GOLDEN_CHESTPLATE, Component.text("Kit 2").color(NamedTextColor.GOLD),
                choice == 2 ? "SELECTED" : "Left-click to select this kit.", "Right-click to edit this kit.");
        ItemStack kit3 = createGuiItem(Material.IRON_CHESTPLATE, Component.text("Kit 3").color(NamedTextColor.GRAY),
                choice == 3 ? "SELECTED" : "Left-click to select this kit.", "Right-click to edit this kit.");
        // (Selected kit keeps its enchant glint below; right-click still edits it too.)

        ItemStack selectedItem = choice == 1 ? kit1 : (choice == 2 ? kit2 : kit3);
        ItemMeta meta = selectedItem.getItemMeta();
        if (meta != null) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            selectedItem.setItemMeta(meta);
        }

        inv.setItem(11, kit1);
        inv.setItem(13, kit2);
        inv.setItem(15, kit3);
        inv.setItem(22, createGuiItem(Material.ARROW, Component.text("Back").color(NamedTextColor.GRAY)));
        player.openInventory(inv);
    }

    // ---------- Spawns menu ----------

    private void openSpawnsMenuGUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("Spawn Setup").color(NamedTextColor.DARK_GRAY));
        inv.setItem(11, createGuiItem(Material.PLAYER_HEAD, Component.text("FFA Spawns").color(NamedTextColor.RED), "Set up spawns for Free-For-All."));
        inv.setItem(15, createGuiItem(Material.WHITE_BANNER, Component.text("Team Spawns").color(NamedTextColor.AQUA), "Set up spawns for each team."));
        inv.setItem(22, createGuiItem(Material.ARROW, Component.text("Back").color(NamedTextColor.GRAY)));
        player.openInventory(inv);
    }

    private void openFfaSpawnChoiceGUI(Player player) {
        int n = ffaPendingPlayers.size();
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("FFA Spawn Setup").color(NamedTextColor.DARK_GRAY));
        inv.setItem(11, createGuiItem(Material.WHITE_WOOL, Component.text("Exact Spawn Points").color(NamedTextColor.WHITE),
                "Place one white wool per player (" + n + " needed).", "Based on the current FFA roster."));
        inv.setItem(15, createGuiItem(Material.BEACON, Component.text("Radius Spawn Point").color(NamedTextColor.AQUA),
                "Place a single white wool.", "Players spread out around it in a circle,", "sized to fit " + n + " players."));
        inv.setItem(22, createGuiItem(Material.ARROW, Component.text("Back").color(NamedTextColor.GRAY)));
        player.openInventory(inv);
    }

    // ==========================================
    // Event Listeners (GUI Clicks & Spawns)
    // ==========================================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;

        String title = PlainTextComponentSerializer.plainText().serialize(e.getView().title());

        if (title.equals("Custom Teams Settings")) {
            e.setCancelled(true);
            if (e.getRawSlot() == 10) openTeamsMenuGUI(player);
            else if (e.getRawSlot() == 12) openSpawnsMenuGUI(player);
            else if (e.getRawSlot() == 14) openPlayerKitChoiceGUI(player);
            else if (e.getRawSlot() == 16) openGameOptionsGUI(player);
            else if (e.getRawSlot() == 22) {
                player.closeInventory();
                if (eventRunning) {
                    endEvent();
                } else if (countdownActive) {
                    if (player.getUniqueId().equals(countdownInitiator)) {
                        if (countdownTask != null) countdownTask.cancel();
                        countdownActive = false;
                        countdownInitiator = null;
                        Bukkit.broadcast(Component.text("Event start cancelled.").color(NamedTextColor.RED));
                    } else {
                        player.sendMessage(Component.text("Only the person who initiated the start can cancel it.").color(NamedTextColor.RED));
                    }
                } else {
                    beginStartCountdown(player);
                }
            }
        }
        else if (title.equals("Game Options")) {
            e.setCancelled(true);
            if (e.getRawSlot() == 10) {
                targetGameMode = (targetGameMode == GameMode.SURVIVAL) ? GameMode.ADVENTURE : GameMode.SURVIVAL;
                getConfig().set("settings.gamemode-on-start", targetGameMode.name());
                saveConfig();
                openGameOptionsGUI(player);
            } else if (e.getRawSlot() == 11) {
                naturalRegenEnabled = !naturalRegenEnabled;
                getConfig().set("settings.natural-regen", naturalRegenEnabled);
                saveConfig();
                openGameOptionsGUI(player);
            } else if (e.getRawSlot() == 12) {
                healthOnKill = !healthOnKill;
                getConfig().set("settings.health-on-kill", healthOnKill);
                saveConfig();
                openGameOptionsGUI(player);
            } else if (e.getRawSlot() == 14) {
                startCountdownSeconds = cycleTime(startCountdownSeconds);
                getConfig().set("settings.start-countdown", startCountdownSeconds);
                saveConfig();
                openGameOptionsGUI(player);
            } else if (e.getRawSlot() == 15) {
                inventoryManagementSeconds = cycleTime(inventoryManagementSeconds);
                getConfig().set("settings.inventory-management-time", inventoryManagementSeconds);
                saveConfig();
                openGameOptionsGUI(player);
            } else if (e.getRawSlot() == 16) {
                celebrationSeconds = cycleTime(celebrationSeconds);
                getConfig().set("settings.celebration-time", celebrationSeconds);
                saveConfig();
                openGameOptionsGUI(player);
            } else if (e.getRawSlot() == 19) {
                healthIndicatorEnabled = !healthIndicatorEnabled;
                getConfig().set("settings.health-indicator", healthIndicatorEnabled);
                saveConfig();
                applyHealthIndicatorDisplay();
                openGameOptionsGUI(player);
            } else if (e.getRawSlot() == 20) {
                itemDropEnabled = !itemDropEnabled;
                getConfig().set("settings.item-drop-enabled", itemDropEnabled);
                saveConfig();
                openGameOptionsGUI(player);
            } else if (e.getRawSlot() == 21) {
                spawnInvincibilitySeconds = cycleTime(spawnInvincibilitySeconds);
                getConfig().set("settings.spawn-invincibility", spawnInvincibilitySeconds);
                saveConfig();
                openGameOptionsGUI(player);
            } else if (e.getRawSlot() == 22) {
                dropItemsOnDeath = !dropItemsOnDeath;
                getConfig().set("settings.drop-items-on-death", dropItemsOnDeath);
                saveConfig();
                openGameOptionsGUI(player);
            } else if (e.getRawSlot() == 26) {
                openMainGUI(player);
            }
        }
        else if (title.equals("Team Management")) {
            e.setCancelled(true);
            if (e.getRawSlot() == 4) {
                queuedMode = (queuedMode == EventMode.TEAMS) ? EventMode.FFA : EventMode.TEAMS;
                openTeamsMenuGUI(player);
            }
            else if (e.getRawSlot() == 11) openFFASelectGUI(player, 0);
            else if (e.getRawSlot() == 14) openColorSelectGUI(player);
            else if (e.getRawSlot() == 15) {
                autofillTeams(player);
                openTeamsMenuGUI(player);
            }
            else if (e.getRawSlot() == 22) openMainGUI(player);
        }
        else if (title.equals("FFA Participant Selector")) {
            e.setCancelled(true);
            Set<UUID> selected = ffaSelections.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
            int page = ffaSelectPage.getOrDefault(player.getUniqueId(), 0);

            if (e.getRawSlot() == 45) {
                selected.clear();
                openFFASelectGUI(player, page);
            } else if (e.getRawSlot() == 46) {
                openFFASelectGUI(player, Math.max(0, page - 1));
            } else if (e.getRawSlot() == 49) {
                openTeamsMenuGUI(player);
            } else if (e.getRawSlot() == 52) {
                openFFASelectGUI(player, page + 1);
            } else if (e.getRawSlot() == 53) {
                ffaPendingPlayers = new HashSet<>(selected);
                queuedMode = EventMode.FFA;
                for (UUID uuid : ffaPendingPlayers) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) p.sendMessage(Component.text("You are registered for the next FFA event!").color(NamedTextColor.WHITE).decorate(TextDecoration.BOLD));
                }
                player.sendMessage(Component.text("FFA roster set with " + ffaPendingPlayers.size() + " players!").color(NamedTextColor.GREEN));
                selected.clear();
                openTeamsMenuGUI(player);
            } else if (e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.PLAYER_HEAD) {
                SkullMeta meta = (SkullMeta) e.getCurrentItem().getItemMeta();
                if (meta != null && meta.getOwningPlayer() != null) {
                    UUID targetId = meta.getOwningPlayer().getUniqueId();
                    if (selected.contains(targetId)) selected.remove(targetId);
                    else selected.add(targetId);
                    openFFASelectGUI(player, page);
                }
            }
        }
        else if (title.equals("Select a Team Color")) {
            e.setCancelled(true);
            if (e.getRawSlot() == 22) openTeamsMenuGUI(player);
            else if (e.getCurrentItem() != null && EventTeam.fromWool(e.getCurrentItem().getType()) != null) {
                openPlayerAssignGUI(player, EventTeam.fromWool(e.getCurrentItem().getType()));
            }
        }
        else if (title.startsWith("Assign: ")) {
            e.setCancelled(true);
            if (e.getRawSlot() == 53) openColorSelectGUI(player);
            else if (e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.PLAYER_HEAD) {
                SkullMeta meta = (SkullMeta) e.getCurrentItem().getItemMeta();
                if (meta != null && meta.getOwningPlayer() != null) {
                    Player target = meta.getOwningPlayer().getPlayer();
                    if (target != null) {
                        EventTeam editingColor = adminEditingTeam.get(player.getUniqueId());
                        playerTeams.put(target.getUniqueId(), editingColor);
                        queuedMode = EventMode.TEAMS;
                        target.sendMessage(Component.text("You joined team: ").color(NamedTextColor.GRAY).append(Component.text(editingColor.getDisplayName()).color(editingColor.getScoreboardColor()).decorate(TextDecoration.BOLD)));
                        openPlayerAssignGUI(player, editingColor);
                    }
                }
            }
        }
        else if (title.startsWith("Master Kit Creator")) {
            if (e.getRawSlot() >= 0 && e.getRawSlot() < 17) {
                ItemStack clicked = e.getCurrentItem();
                if (clicked != null && clicked.getType().name().endsWith("GLASS_PANE")) {
                    e.setCancelled(true);
                    ItemStack cursorItem = e.getCursor();
                    if (cursorItem != null && cursorItem.getType() != Material.AIR) {
                        e.setCurrentItem(cursorItem.clone());
                        e.getView().setCursor(null);
                    }
                }
            }

            if (e.getRawSlot() == 17) {
                e.setCancelled(true);
                int kitNumber = editingKitNumber.getOrDefault(player.getUniqueId(), 1);
                saveKitFromGUI(player, e.getInventory(), kitNumber);
                player.closeInventory();
                Bukkit.getScheduler().runTask(this, () -> openPlayerKitChoiceGUI(player));
            }
        }
        else if (title.equals("My Kits")) {
            e.setCancelled(true);
            int kitNumber = e.getRawSlot() == 11 ? 1 : e.getRawSlot() == 13 ? 2 : e.getRawSlot() == 15 ? 3 : 0;
            if (kitNumber != 0) {
                if (e.getClick().isRightClick()) {
                    openMasterKitEditorGUI(player, kitNumber);
                } else {
                    kitsDb.set("playerkits." + player.getUniqueId() + ".choice", kitNumber);
                    saveKitsDatabase();
                    player.sendMessage(Component.text("You selected Kit " + kitNumber + "!").color(NamedTextColor.GREEN));
                    openPlayerKitChoiceGUI(player);
                }
            } else if (e.getRawSlot() == 22) {
                openMainGUI(player);
            }
        }
        else if (title.equals("Spawn Setup")) {
            e.setCancelled(true);
            if (e.getRawSlot() == 11) {
                if (ffaPendingPlayers.isEmpty()) {
                    player.sendMessage(Component.text("Select FFA participants first (Team Management > Free-For-All).").color(NamedTextColor.RED));
                } else {
                    openFfaSpawnChoiceGUI(player);
                }
            } else if (e.getRawSlot() == 15) {
                enterTeamSpawnEditMode(player);
            } else if (e.getRawSlot() == 22) {
                openMainGUI(player);
            }
        }
        else if (title.equals("FFA Spawn Setup")) {
            e.setCancelled(true);
            if (e.getRawSlot() == 11) {
                enterFfaExactSpawnEditMode(player, ffaPendingPlayers.size());
            } else if (e.getRawSlot() == 15) {
                enterFfaRadiusSpawnEditMode(player, ffaPendingPlayers.size());
            } else if (e.getRawSlot() == 22) {
                openSpawnsMenuGUI(player);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player)) return;
        String title = PlainTextComponentSerializer.plainText().serialize(e.getView().title());

        if (title.startsWith("Master Kit Creator") && savedKitBuilderInventories.containsKey(player.getUniqueId())) {
            player.setGameMode(savedKitBuilderGameModes.get(player.getUniqueId()));
            player.getInventory().setContents(savedKitBuilderInventories.get(player.getUniqueId()));
            savedKitBuilderInventories.remove(player.getUniqueId());
            savedKitBuilderGameModes.remove(player.getUniqueId());
        }
    }

    // ==========================================
    // Team Spawn Editing (hotbar tool layout)
    // ==========================================

    private void enterTeamSpawnEditMode(Player player) {
        player.closeInventory();
        UUID id = player.getUniqueId();
        teamSpawnEditMode.add(id);
        teamSpawnEditPrevGameMode.put(id, player.getGameMode());

        Map<EventTeam, List<Location>> buffer = new EnumMap<>(EventTeam.class);
        for (EventTeam team : EventTeam.values()) {
            List<Location> existing = new ArrayList<>();
            List<?> raw = kitsDb.getList("spawns." + team.name());
            if (raw != null) for (Object o : raw) if (o instanceof Location loc) existing.add(loc);
            buffer.put(team, existing);
        }
        teamSpawnBuffer.put(id, buffer);

        player.getInventory().clear();
        player.setGameMode(GameMode.CREATIVE);

        EventTeam[] teams = EventTeam.values();
        for (int i = 0; i < teams.length; i++) {
            player.getInventory().setItem(i, createGuiItem(teams[i].getWool(),
                    Component.text(teams[i].getDisplayName() + " Team Spawnpoint").color(teams[i].getScoreboardColor()),
                    "Place to add a spawn location for this team."));
        }
        for (int i = 0; i < teams.length; i++) {
            player.getInventory().setItem(4 + i, createGuiItem(teams[i].getResetDye(),
                    Component.text(teams[i].getDisplayName() + " Team Reset").color(teams[i].getScoreboardColor()),
                    "Right-click to clear only this team's spawns."));
        }
        player.getInventory().setItem(8, createGuiItem(Material.EMERALD_BLOCK, Component.text("Save and Exit").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD)));

        player.getInventory().setItem(9, createGuiItem(Material.BARRIER, Component.text("Cancel").color(NamedTextColor.RED).decorate(TextDecoration.BOLD), "Discard changes without saving."));
        player.getInventory().setItem(10, createGuiItem(Material.TNT, Component.text("Reset All").color(NamedTextColor.DARK_RED).decorate(TextDecoration.BOLD), "Clear every team's spawns."));

        player.sendMessage(Component.text("Place wool to add a team spawn point. Use the reset items or the Save/Cancel tools to finish.").color(NamedTextColor.LIGHT_PURPLE));
    }

    private void exitTeamSpawnEditMode(Player player, boolean save) {
        UUID id = player.getUniqueId();
        teamSpawnEditMode.remove(id);
        Map<EventTeam, List<Location>> buffer = teamSpawnBuffer.remove(id);

        if (save && buffer != null) {
            for (EventTeam team : EventTeam.values()) {
                kitsDb.set("spawns." + team.name(), buffer.get(team));
            }
            saveKitsDatabase();
            player.sendMessage(Component.text("Team spawns saved!").color(NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("Team spawn changes discarded.").color(NamedTextColor.YELLOW));
        }

        player.getInventory().clear();
        GameMode restoreMode = teamSpawnEditPrevGameMode.remove(id);
        player.setGameMode(restoreMode != null ? restoreMode : GameMode.SURVIVAL);
        openMainGUI(player);
    }

    private List<Location> getTeamSpawnPoints(EventTeam team) {
        List<?> raw = kitsDb.getList("spawns." + team.name());
        List<Location> locs = new ArrayList<>();
        if (raw != null) for (Object o : raw) if (o instanceof Location loc) locs.add(loc);
        return locs;
    }

    /**
     * Spreads a team's members across its configured spawn points as evenly as
     * possible. With at least as many spawns as members, every player gets a
     * distinct point. With fewer spawns than members, the shuffled roster
     * wraps around the shuffled spawn list (index % spawnCount), so only as
     * many players as strictly necessary end up sharing a single point (e.g.
     * 4 players / 3 spawns -> exactly one pair shares one point) rather than
     * everyone landing on the same random spot. Each resulting location is
     * also snapped to solid ground via findSafeLocation so nobody spawns
     * hovering, even if the world has changed since the spawn was set.
     */
    private Map<UUID, Location> assignTeamSpawns(List<Player> members, List<Location> spawns) {
        Map<UUID, Location> result = new HashMap<>();
        if (spawns.isEmpty() || members.isEmpty()) return result;

        List<Location> shuffledSpawns = new ArrayList<>(spawns);
        Collections.shuffle(shuffledSpawns);
        List<Player> shuffledMembers = new ArrayList<>(members);
        Collections.shuffle(shuffledMembers);

        int spawnCount = shuffledSpawns.size();
        for (int i = 0; i < shuffledMembers.size(); i++) {
            Location rawSpawn = shuffledSpawns.get(i % spawnCount);
            result.put(shuffledMembers.get(i).getUniqueId(), findSafeLocation(rawSpawn));
        }
        return result;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        Player player = e.getPlayer();
        UUID id = player.getUniqueId();
        Material type = e.getBlockPlaced().getType();

        if (teamSpawnEditMode.contains(id)) {
            EventTeam team = EventTeam.fromWool(type);
            if (team != null) {
                e.setCancelled(true);
                Location loc = e.getBlockPlaced().getLocation().add(0.5, 1, 0.5);
                if (!isSafe(loc)) {
                    player.sendMessage(Component.text("Warning: that spot looks unsafe (players may suffocate or fall).").color(NamedTextColor.RED));
                }
                teamSpawnBuffer.computeIfAbsent(id, k -> new EnumMap<>(EventTeam.class))
                        .computeIfAbsent(team, k -> new ArrayList<>()).add(loc);
                player.sendMessage(Component.text("Added a spawn point for ").color(NamedTextColor.GRAY).append(Component.text(team.getDisplayName()).color(team.getScoreboardColor())));
            } else {
                e.setCancelled(true);
            }
            return;
        }

        if (ffaSpawnEditMode.contains(id) && type == Material.WHITE_WOOL) {
            e.setCancelled(true);
            String mode = ffaSpawnEditType.get(id);
            Location loc = e.getBlockPlaced().getLocation().add(0.5, 1, 0.5);

            if ("EXACT".equals(mode)) {
                List<Location> buffer = ffaExactSpawnBuffer.computeIfAbsent(id, k -> new ArrayList<>());
                int needed = ffaExactSpawnNeeded.getOrDefault(id, 0);
                if (buffer.size() >= needed) {
                    player.sendMessage(Component.text("You already have enough spawn points (" + needed + "). Save & Exit to finish.").color(NamedTextColor.YELLOW));
                    return;
                }
                if (!isSafe(loc)) {
                    player.sendMessage(Component.text("Warning: that spot looks unsafe (players may suffocate or fall).").color(NamedTextColor.RED));
                }
                buffer.add(loc);
                player.sendMessage(Component.text("Spawn point added (" + buffer.size() + "/" + needed + ").").color(NamedTextColor.GREEN));
            } else if ("RADIUS".equals(mode)) {
                kitsDb.set("ffaspawns.mode", "RADIUS");
                kitsDb.set("ffaspawns.center", loc);
                saveKitsDatabase();
                player.sendMessage(Component.text("Radius center point set! Players will spread out around it. Save & Exit to finish.").color(NamedTextColor.GREEN));
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = e.getPlayer();
        UUID id = player.getUniqueId();
        ItemStack item = e.getItem();
        if (item == null) return;

        if (teamSpawnEditMode.contains(id)) {
            EventTeam resetTeam = EventTeam.fromResetDye(item.getType());
            if (resetTeam != null) {
                e.setCancelled(true);
                Map<EventTeam, List<Location>> buffer = teamSpawnBuffer.get(id);
                if (buffer != null) buffer.put(resetTeam, new ArrayList<>());
                player.sendMessage(Component.text("Cleared pending spawns for ").color(NamedTextColor.GRAY).append(Component.text(resetTeam.getDisplayName()).color(resetTeam.getScoreboardColor())));
                return;
            }
            if (item.getType() == Material.EMERALD_BLOCK) {
                e.setCancelled(true);
                exitTeamSpawnEditMode(player, true);
                return;
            }
            if (item.getType() == Material.BARRIER) {
                e.setCancelled(true);
                exitTeamSpawnEditMode(player, false);
                return;
            }
            if (item.getType() == Material.TNT) {
                e.setCancelled(true);
                Map<EventTeam, List<Location>> buffer = teamSpawnBuffer.get(id);
                if (buffer != null) for (EventTeam team : EventTeam.values()) buffer.put(team, new ArrayList<>());
                player.sendMessage(Component.text("Cleared all pending team spawns.").color(NamedTextColor.RED));
                return;
            }
        }

        if (ffaSpawnEditMode.contains(id)) {
            if (item.getType() == Material.EMERALD_BLOCK) {
                e.setCancelled(true);
                exitFfaSpawnEditMode(player, true);
                return;
            }
            if (item.getType() == Material.BARRIER) {
                e.setCancelled(true);
                exitFfaSpawnEditMode(player, false);
                return;
            }
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (teamSpawnEditMode.contains(id) || ffaSpawnEditMode.contains(id)) {
            e.setCancelled(true);
            return;
        }
        if (!itemDropEnabled && eventRunning && isParticipant(id)) {
            e.setCancelled(true);
        }
    }

    // ==========================================
    // FFA Spawn Editing
    // ==========================================

    private void enterFfaExactSpawnEditMode(Player player, int needed) {
        player.closeInventory();
        UUID id = player.getUniqueId();
        ffaSpawnEditMode.add(id);
        ffaSpawnEditType.put(id, "EXACT");
        ffaExactSpawnNeeded.put(id, needed);
        ffaExactSpawnBuffer.put(id, new ArrayList<>());
        ffaSpawnEditPrevGameMode.put(id, player.getGameMode());

        player.getInventory().clear();
        player.setGameMode(GameMode.CREATIVE);

        player.getInventory().setItem(8, createGuiItem(Material.EMERALD_BLOCK, Component.text("Save and Exit").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD)));
        player.getInventory().setItem(35, createGuiItem(Material.BARRIER, Component.text("Cancel").color(NamedTextColor.RED).decorate(TextDecoration.BOLD)));

        int[] usableSlots = { 0, 1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34 };
        int woolToGive = needed;
        int i = 0;
        while (woolToGive > 0 && i < usableSlots.length) {
            int stackSize = Math.min(64, woolToGive);
            player.getInventory().setItem(usableSlots[i], new ItemStack(Material.WHITE_WOOL, stackSize));
            woolToGive -= stackSize;
            i++;
        }

        player.sendMessage(Component.text("Place " + needed + " white wool blocks, one per player spawn point.").color(NamedTextColor.LIGHT_PURPLE));
    }

    private void enterFfaRadiusSpawnEditMode(Player player, int n) {
        player.closeInventory();
        UUID id = player.getUniqueId();
        ffaSpawnEditMode.add(id);
        ffaSpawnEditType.put(id, "RADIUS");
        ffaSpawnEditPrevGameMode.put(id, player.getGameMode());

        player.getInventory().clear();
        player.setGameMode(GameMode.CREATIVE);

        player.getInventory().setItem(0, new ItemStack(Material.WHITE_WOOL, 1));
        player.getInventory().setItem(8, createGuiItem(Material.EMERALD_BLOCK, Component.text("Save and Exit").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD)));
        player.getInventory().setItem(35, createGuiItem(Material.BARRIER, Component.text("Cancel").color(NamedTextColor.RED).decorate(TextDecoration.BOLD)));

        player.sendMessage(Component.text("Place the single white wool as the center point. Players (" + n + ") will spread out around it based on distance.").color(NamedTextColor.LIGHT_PURPLE));
    }

    private void exitFfaSpawnEditMode(Player player, boolean save) {
        UUID id = player.getUniqueId();
        String mode = ffaSpawnEditType.remove(id);
        ffaSpawnEditMode.remove(id);

        if (save && "EXACT".equals(mode)) {
            List<Location> buffer = ffaExactSpawnBuffer.getOrDefault(id, new ArrayList<>());
            int needed = ffaExactSpawnNeeded.getOrDefault(id, 0);
            if (buffer.size() < needed) {
                player.sendMessage(Component.text("You only placed " + buffer.size() + "/" + needed + " spawn points. Saving anyway; spawns will cycle.").color(NamedTextColor.YELLOW));
            }
            kitsDb.set("ffaspawns.mode", "EXACT");
            kitsDb.set("ffaspawns.points", buffer);
            saveKitsDatabase();
            player.sendMessage(Component.text("FFA exact spawn points saved!").color(NamedTextColor.GREEN));
        } else if (!save) {
            player.sendMessage(Component.text("FFA spawn changes discarded.").color(NamedTextColor.YELLOW));
        } else {
            // RADIUS mode already saved directly on placement
            player.sendMessage(Component.text("FFA radius spawn saved!").color(NamedTextColor.GREEN));
        }

        ffaExactSpawnBuffer.remove(id);
        ffaExactSpawnNeeded.remove(id);
        player.getInventory().clear();
        GameMode restoreMode = ffaSpawnEditPrevGameMode.remove(id);
        player.setGameMode(restoreMode != null ? restoreMode : GameMode.SURVIVAL);
        openMainGUI(player);
    }

    private List<Location> resolveFfaSpawns(int n) {
        List<Location> spawns = new ArrayList<>();
        if (n <= 0) return spawns;
        String mode = kitsDb.getString("ffaspawns.mode");

        if ("EXACT".equals(mode)) {
            List<?> raw = kitsDb.getList("ffaspawns.points");
            if (raw != null) for (Object o : raw) if (o instanceof Location loc) spawns.add(findSafeLocation(loc));
            Collections.shuffle(spawns);
        } else if ("RADIUS".equals(mode)) {
            Location center = kitsDb.getLocation("ffaspawns.center");
            if (center != null) spawns = calculateRadiusSpawns(center, n);
        }
        return spawns;
    }

    /**
     * Places players evenly around a circle whose radius scales with player count,
     * so more participants spread further from the center point.
     */
    private List<Location> calculateRadiusSpawns(Location center, int n) {
        List<Location> result = new ArrayList<>();
        double radius = 5 + (n * 1.5);
        for (int i = 0; i < n; i++) {
            double angle = (2 * Math.PI / n) * i;
            double dx = Math.cos(angle) * radius;
            double dz = Math.sin(angle) * radius;
            Location candidate = center.clone().add(dx, 0, dz);
            result.add(findSafeLocation(candidate));
        }
        return result;
    }

    /**
     * Searches near a candidate column for a safe standing spot (solid ground,
     * two passable blocks above it, no lava/fire) to avoid spawning players
     * inside blocks where they would immediately suffocate or take damage
     */
    private Location findSafeLocation(Location loc) {
        World world = loc.getWorld();
        if (world == null) return loc;
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int startY = loc.getBlockY();

        for (int dy = 0; dy <= 15; dy++) {
            Location up = new Location(world, x + 0.5, Math.min(startY + dy, world.getMaxHeight() - 2), z + 0.5);
            if (isSafe(up)) return up;
            if (dy > 0) {
                Location down = new Location(world, x + 0.5, Math.max(startY - dy, world.getMinHeight() + 1), z + 0.5);
                if (isSafe(down)) return down;
            }
        }

        int highestY = world.getHighestBlockYAt(x, z);
        return new Location(world, x + 0.5, highestY + 1, z + 0.5);
    }

    private boolean isSafe(Location feet) {
        Block feetBlock = feet.getBlock();
        Block headBlock = feetBlock.getRelative(0, 1, 0);
        Block groundBlock = feetBlock.getRelative(0, -1, 0);

        boolean feetPassable = !feetBlock.getType().isSolid();
        boolean headPassable = !headBlock.getType().isSolid();
        boolean groundSolid = groundBlock.getType().isSolid();
        boolean notDangerous = feetBlock.getType() != Material.LAVA && feetBlock.getType() != Material.FIRE
                && headBlock.getType() != Material.LAVA && groundBlock.getType() != Material.LAVA;

        return feetPassable && headPassable && groundSolid && notDangerous;
    }

    // ==========================================
    // Team & Game Interactions
    // ==========================================

    private static final int TOTAL_KITS = 3;

    /**
     * Tries the chosen kit first, then cycles through the remaining kit slots
     * (in order) looking for the first one that has actually been built (54
     * saved slots) among the owner's 3 personal kits, so nobody ends up
     * empty-handed just because the preferred kit hasn't been designed yet.
     * "kitOwnerId" is whoever's kit selection is being used - see giveKit().
     */
    private List<ItemStack> resolveKitContents(UUID kitOwnerId, int choice) {
        for (int attempt = 0; attempt < TOTAL_KITS; attempt++) {
            int kitNum = ((choice - 1 + attempt) % TOTAL_KITS) + 1;
            @SuppressWarnings("unchecked")
            List<ItemStack> kit = (List<ItemStack>) kitsDb.getList("playerkits." + kitOwnerId + ".kit" + kitNum);
            if (kit != null && kit.size() == 54) return kit;
        }
        return null;
    }

    /**
     * Everyone in the match uses the host's kit - the kit belonging to
     * whoever clicked "Start" (eventHostId) - EXCEPT the host themself, who
     * of course still uses their own. Players still build and choose among
     * their own 3 kits in "My Kits"; that selection only actually gets
     * equipped when they are hosting the event.
     */
    private void giveKit(Player player) {
        UUID playerId = player.getUniqueId();
        UUID kitOwnerId = (eventHostId != null) ? eventHostId : playerId;
        int choice = kitsDb.getInt("playerkits." + kitOwnerId + ".choice", 1);
        List<ItemStack> kitContents = resolveKitContents(kitOwnerId, choice);

        if (kitContents != null && kitContents.size() == 54) {
            player.getInventory().clear();

            player.getInventory().setHelmet(kitContents.get(0));
            player.getInventory().setChestplate(kitContents.get(1));
            player.getInventory().setLeggings(kitContents.get(2));
            player.getInventory().setBoots(kitContents.get(3));
            player.getInventory().setItemInOffHand(kitContents.get(4));

            for (int i = 5; i <= 16; i++) {
                ItemStack potionItem = kitContents.get(i);
                if (potionItem != null && potionItem.hasItemMeta() && potionItem.getItemMeta() instanceof PotionMeta meta) {
                    if (meta.hasCustomEffects()) {
                        player.addPotionEffects(meta.getCustomEffects());
                    } else if (meta.getBasePotionType() != null) {
                        for (PotionEffect effect : meta.getBasePotionType().getPotionEffects()) {
                            player.addPotionEffect(effect);
                        }
                    }
                }
            }

            for (int i = 0; i < 27; i++) {
                ItemStack item = kitContents.get(18 + i);
                if (item != null) player.getInventory().setItem(i + 9, item);
            }

            for (int i = 0; i < 9; i++) {
                ItemStack item = kitContents.get(45 + i);
                if (item != null) player.getInventory().setItem(i, item);
            }
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        boolean participant = eventRunning && isParticipant(id);
        NamedTextColor color = participant ? getParticipantColor(id) : null;

        e.renderer((source, sourceDisplayName, message, viewer) -> {
            if (!participant || color == null || spectatingPlayers.contains(id)) {
                return Component.text("<").color(NamedTextColor.GRAY).append(sourceDisplayName).append(Component.text("> ").color(NamedTextColor.GRAY)).append(message.color(NamedTextColor.WHITE));
            } else {
                Component prefix = Component.text("<").color(NamedTextColor.GRAY)
                        .append(sourceDisplayName.color(color).decorate(TextDecoration.BOLD))
                        .append(Component.text("> ").color(NamedTextColor.GRAY));

                return prefix.append(message.color(NamedTextColor.WHITE));
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (!eventRunning || !isParticipant(id)) return;

        if (currentMode == EventMode.FFA) {
            p.playerListName(Component.text(p.getName()).color(NamedTextColor.WHITE));
        } else {
            EventTeam team = playerTeams.get(id);
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            Team sTeam = board.getTeam("CT_" + team.name());
            if (sTeam != null) {
                sTeam.addEntry(p.getName());
                p.playerListName(Component.text(p.getName()).color(team.getScoreboardColor()));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        handleLeaveEvent(e.getPlayer());
    }

    // ==========================================
    // Utils
    // ==========================================

    private ItemStack createGuiItem(Material material, Component name, String... lore) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            if (lore.length > 0) {
                List<Component> loreList = new ArrayList<>();
                for (String line : lore) {
                    loreList.add(Component.text(line).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(loreList);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}