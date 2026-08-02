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
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
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
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.LingeringPotionSplashEvent;
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

    // World game-rule toggles (applied server-wide to every loaded world)
    private boolean mobGriefingEnabled = true;     // gamerule mobGriefing (explosions breaking blocks, etc.)
    private boolean daylightCycleEnabled = true;   // gamerule doDaylightCycle (time of day progression)
    private boolean mobSpawningEnabled = true;     // gamerule doMobSpawning
    private boolean weatherCycleEnabled = true;    // gamerule doWeatherCycle

    // Lingering potions thrown as "smoke grenades" - spawns a poof particle burst
    // at the area effect cloud's location for as long as the cloud lives, instead
    // of relying on a repeating /execute at @e[type=area_effect_cloud] command.
    private boolean lingeringSmokeGrenadesEnabled = false;

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


    // Teams mode
    private final Map<UUID, EventTeam> playerTeams = new HashMap<>();
    private final Map<UUID, EventTeam> adminEditingTeam = new HashMap<>();

    // FFA mode
    private final Map<UUID, Set<UUID>> ffaSelections = new HashMap<>(); // in-progress selector state per admin
    private final Map<UUID, Integer> ffaSelectPage = new HashMap<>();
    private Set<UUID> ffaPendingPlayers = new HashSet<>(); // finalized roster for the next FFA start
    private final Set<UUID> ffaPlayers = new HashSet<>();  // active participants while FFA is running

    // ==========================================
    // Lives System
    // ==========================================
    private int maxLives = 1; // Game Options counter, minimum 1
    private final Map<UUID, Integer> livesRemaining = new HashMap<>(); // per-participant lives left this match

    // ==========================================
    // Kit System (revamped: unlimited, admin-managed kits)
    // ==========================================
    private static final String UNIVERSAL_TEAM = "UNIVERSAL";
    private boolean kitSelectionPhaseEnabled = false; // global game option
    // Which kit (by id, within the event host's kit library) each participant is
    // currently using this match - used so lives-respawns re-equip the same kit.
    private final Map<UUID, String> assignedKitId = new HashMap<>();
    // In-progress kit selection during the Kit Selection Phase: participant -> kitId chosen so far.
    private final Map<UUID, String> kitSelectionChoice = new HashMap<>();
    // Participants who currently have the "Choose Your Kit" GUI open, so it can be live-refreshed.
    private final Set<UUID> kitSelectionGuiOpenPlayers = new HashSet<>();
    private BukkitTask kitSelectionTimerTask = null;
    private boolean kitSelectionPhaseActive = false;

    // Kit list / editor GUI paging + in-progress state
    private final Map<UUID, Integer> kitListPage = new HashMap<>();
    private final Map<UUID, String> editingKitId = new HashMap<>();
    // Player is expected to type a new kit name in chat next (Rename button)
    private final Map<UUID, String> awaitingKitNameInput = new HashMap<>();

    // In-progress (unsaved) kit builder state, indexed exactly like a saved kit's
    // 54-slot "contents" list (0-4 armor+offhand, 5-16 potions, 17 unused,
    // 18-53 the 36-slot inventory). Populated when editing begins and kept in
    // sync as the admin navigates between the main editor / potions / toggles
    // sub-screens, so nothing is lost until they back all the way out.
    private final Map<UUID, ItemStack[]> kitBuilderStage = new HashMap<>();
    // The icon item currently staged for the kit being edited (null = no custom icon).
    private final Map<UUID, ItemStack> kitBuilderIcon = new HashMap<>();
    // Set right before switching between kit-editor sub-screens so the
    // InventoryCloseEvent this triggers doesn't restore the admin's real
    // inventory/gamemode or wipe the in-progress kit builder state.
    private final Set<UUID> kitEditorInternalNav = new HashSet<>();

    private static final Material ICON_PLACEHOLDER = Material.YELLOW_STAINED_GLASS_PANE;
    private static final Material POTION_PLACEHOLDER = Material.MAGENTA_STAINED_GLASS_PANE;
    private static final Material HELMET_PLACEHOLDER = Material.RED_STAINED_GLASS_PANE;
    private static final Material CHESTPLATE_PLACEHOLDER = Material.RED_STAINED_GLASS_PANE;
    private static final Material LEGGINGS_PLACEHOLDER = Material.RED_STAINED_GLASS_PANE;
    private static final Material BOOTS_PLACEHOLDER = Material.RED_STAINED_GLASS_PANE;
    private static final Material OFFHAND_PLACEHOLDER = Material.BLUE_STAINED_GLASS_PANE;

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
    private BukkitTask livesActionBarTask = null;

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
        maxLives = Math.max(1, getConfig().getInt("settings.max-lives", 1));
        kitSelectionPhaseEnabled = getConfig().getBoolean("settings.kit-selection-phase", false);
        mobGriefingEnabled = getConfig().getBoolean("settings.mob-griefing", true);
        daylightCycleEnabled = getConfig().getBoolean("settings.daylight-cycle", true);
        mobSpawningEnabled = getConfig().getBoolean("settings.mob-spawning", true);
        weatherCycleEnabled = getConfig().getBoolean("settings.weather-cycle", true);
        lingeringSmokeGrenadesEnabled = getConfig().getBoolean("settings.lingering-smoke-grenades", false);
        applyAllWorldGameRules();

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

    /** Applies a single boolean gamerule to every currently loaded world. */
    private void applyGameRule(GameRule<Boolean> rule, boolean value) {
        for (World world : Bukkit.getWorlds()) {
            world.setGameRule(rule, value);
        }
    }

    /** Re-applies all four world-toggle game rules (called on enable and whenever one is flipped). */
    private void applyAllWorldGameRules() {
        applyGameRule(GameRule.MOB_GRIEFING, mobGriefingEnabled);
        applyGameRule(GameRule.DO_DAYLIGHT_CYCLE, daylightCycleEnabled);
        applyGameRule(GameRule.DO_MOB_SPAWNING, mobSpawningEnabled);
        applyGameRule(GameRule.DO_WEATHER_CYCLE, weatherCycleEnabled);
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

    /**
     * Shows each participant their own remaining-lives count just above the hotbar
     * (action bar), refreshed periodically since action bar text fades out on its
     * own after a couple seconds. Spectators (out of lives) see nothing.
     */
    private void startLivesActionBarTask() {
        stopLivesActionBarTask();
        livesActionBarTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!eventRunning) { cancel(); return; }

                Set<UUID> participants = currentMode == EventMode.FFA ? ffaPlayers : playerTeams.keySet();
                for (UUID uuid : participants) {
                    if (spectatingPlayers.contains(uuid)) continue;
                    Player p = Bukkit.getPlayer(uuid);
                    if (p == null || !p.isOnline()) continue;
                    int lives = livesRemaining.getOrDefault(uuid, maxLives);
                    p.sendActionBar(Component.text("\u2764 Lives: " + lives).color(NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.BOLD));
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void stopLivesActionBarTask() {
        if (livesActionBarTask != null) {
            livesActionBarTask.cancel();
            livesActionBarTask = null;
        }
    }

    private int cycleTime(int current) {
        if (current <= 0) return 5;
        if (current == 5) return 10;
        return 0;
    }

    /**
     * When the Kit Selection Phase is enabled, the inventory-management timer
     * doubles as the kit-choosing window, so a 0-second timer is disallowed
     * (the GUI needs time to be usable) and 15s/20s options are added on top
     * of the usual 5s/10s. When the phase is disabled, behaves like cycleTime.
     */
    private int cycleInventoryManagementTime(int current) {
        if (!kitSelectionPhaseEnabled) return cycleTime(current);
        if (current <= 5) return 10;
        if (current == 10) return 15;
        if (current == 15) return 20;
        return 5;
    }

    private int cycleLives(int current, int delta) {
        int updated = current + delta;
        if (updated < 1) updated = 1;
        if (updated > 20) updated = 20;
        return updated;
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

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("leave");
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
        startLivesActionBarTask();
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
                livesRemaining.put(uuid, maxLives);
                if (kitSelectionPhaseApplies()) {
                    p.getInventory().clear();
                } else {
                    giveKit(p);
                }
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

                livesRemaining.put(p.getUniqueId(), maxLives);
                if (kitSelectionPhaseApplies()) {
                    p.getInventory().clear();
                } else {
                    giveKit(p);
                }
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
        if (kitSelectionPhaseApplies()) {
            beginKitSelectionPhase(participants);
        } else if (inventoryManagementSeconds > 0) {
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
        stopLivesActionBarTask();
        applyHealthIndicatorDisplay();

        if (kitSelectionTimerTask != null) { kitSelectionTimerTask.cancel(); kitSelectionTimerTask = null; }
        kitSelectionPhaseActive = false;
        kitSelectionChoice.clear();
        kitSelectionGuiOpenPlayers.clear();
        assignedKitId.clear();
        livesRemaining.clear();

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
        livesRemaining.remove(id);
        assignedKitId.remove(id);
        kitSelectionChoice.remove(id);
        kitSelectionGuiOpenPlayers.remove(id);
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
        UUID vid = victim.getUniqueId();
        if (!isParticipant(vid)) return;

        spawnLandingLock.remove(vid);

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

        int remaining = livesRemaining.getOrDefault(vid, maxLives) - 1;
        livesRemaining.put(vid, remaining);

        if (remaining > 0) {
            // Still has lives left - will respawn alive at a team/FFA spawnpoint,
            // so the exact death spot doesn't matter for the respawn location.
            deathLocations.remove(vid);
        } else {
            deathLocations.put(vid, victim.getLocation());
        }

        Bukkit.getScheduler().runTask(this, () -> victim.spigot().respawn());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent e) {
        if (!eventRunning) return;
        Player player = e.getPlayer();
        UUID id = player.getUniqueId();
        if (!isParticipant(id)) return;

        int remaining = livesRemaining.getOrDefault(id, 0);

        if (remaining > 0) {
            // Lives remain: respawn alive at the team's (or FFA's) spawnpoint, not as a spectator.
            Location respawnLoc = resolveLivesRespawnLocation(player);
            if (respawnLoc != null) {
                e.setRespawnLocation(respawnLoc);
                spawnLandingLock.put(id, new double[]{respawnLoc.getX(), respawnLoc.getZ()});
            }

            Bukkit.getScheduler().runTask(this, () -> {
                setupCommonMatchState(player);
                UUID kitOwnerId = currentKitOwnerId();
                giveKitById(player, kitOwnerId != null ? kitOwnerId : id, assignedKitId.get(id));
                grantInvincibility(player, spawnInvincibilitySeconds);
                player.sendMessage(Component.text("You lost a life! Lives remaining: " + remaining).color(NamedTextColor.YELLOW));
                player.showTitle(Title.title(
                        Component.text("Respawned!").color(NamedTextColor.YELLOW).decorate(TextDecoration.BOLD),
                        Component.text(remaining + " " + (remaining == 1 ? "life" : "lives") + " remaining").color(NamedTextColor.GRAY)));
            });
        } else {
            spectatingPlayers.add(id);

            if (deathLocations.containsKey(id)) {
                e.setRespawnLocation(deathLocations.remove(id));
            }

            Bukkit.getScheduler().runTask(this, () -> {
                player.setGameMode(GameMode.SPECTATOR);
                player.sendMessage(Component.text("You died! You are now spectating. Type /ct leave to exit.").color(NamedTextColor.YELLOW));
                updateGameState();
            });
        }
    }

    /** Picks a safe respawn spot for a player who still has lives left: their team's spawn (Teams mode) or a configured FFA spawn. */
    private Location resolveLivesRespawnLocation(Player player) {
        if (currentMode == EventMode.TEAMS) {
            EventTeam team = playerTeams.get(player.getUniqueId());
            if (team != null) {
                List<Location> spawns = getTeamSpawnPoints(team);
                if (!spawns.isEmpty()) {
                    Location raw = spawns.get(new Random().nextInt(spawns.size()));
                    return findSafeLocation(raw);
                }
            }
        } else if (currentMode == EventMode.FFA) {
            List<Location> spawns = resolveFfaSpawns(1);
            if (!spawns.isEmpty()) return spawns.get(0);
        }
        return player.getLocation();
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
     * "Smoke grenade" lingering potions. Replaces a repeating command like:
     *   execute at @e[type=minecraft:area_effect_cloud] run particle minecraft:poof ~ ~1 ~ 1.3 1.3 1.3 0 85 force
     * A command-block loop like that re-scans every area effect cloud on the
     * server on every single tick. This does the same visual (poof burst,
     * force-rendered so it's visible past the normal particle distance) but
     * only for the one cloud that was just created, and only for as long as
     * that specific cloud stays alive - no repeated entity-selector lookups.
     */
    @EventHandler
    public void onLingeringPotionSplash(LingeringPotionSplashEvent e) {
        if (!lingeringSmokeGrenadesEnabled) return;
        AreaEffectCloud cloud = e.getAreaEffectCloud();
        if (cloud == null) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!cloud.isValid() || cloud.isDead()) {
                    cancel();
                    return;
                }
                Location loc = cloud.getLocation().add(0, 1, 0);
                cloud.getWorld().spawnParticle(Particle.POOF, loc, 85, 1.3, 1.3, 1.3, 0, null, true);
            }
        }.runTaskTimer(this, 0L, 1L);
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
        inv.setItem(14, createGuiItem(Material.DIAMOND_SWORD, Component.text("Admin Kits").color(NamedTextColor.GOLD), "Build unlimited kits, assign them to", "teams (or Universal), cap how many", "players may pick each one, and toggle", "the Kit Selection Phase.", "(Only the event host's kits are", "actually used in-match.)"));
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
        Inventory inv = Bukkit.createInventory(null, 45, Component.text("Game Options").color(NamedTextColor.DARK_GRAY));

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

        inv.setItem(23, createGuiItem(Material.TOTEM_OF_UNDYING, Component.text("Lives: " + maxLives).color(NamedTextColor.LIGHT_PURPLE),
                "How many times a player respawns", "at their team's spawnpoint before", "being eliminated to spectator.",
                "Left-click: +1  Right-click: -1  (min 1)"));

        Material griefMat = mobGriefingEnabled ? Material.TNT : Material.BARRIER;
        NamedTextColor griefColor = mobGriefingEnabled ? NamedTextColor.GREEN : NamedTextColor.RED;
        inv.setItem(28, createGuiItem(griefMat, Component.text("Mob Griefing: " + (mobGriefingEnabled ? "ON" : "OFF")).color(griefColor),
                "Whether explosions/mobs can", "break or change blocks.", "Click to toggle. (Server-wide)"));

        Material timeMat = daylightCycleEnabled ? Material.CLOCK : Material.DAYLIGHT_DETECTOR;
        NamedTextColor timeColor = daylightCycleEnabled ? NamedTextColor.GREEN : NamedTextColor.RED;
        inv.setItem(29, createGuiItem(timeMat, Component.text("Time of Day: " + (daylightCycleEnabled ? "ON" : "OFF")).color(timeColor),
                "Whether the day/night cycle", "progresses on its own.", "Click to toggle. (Server-wide)"));

        Material spawnMat = mobSpawningEnabled ? Material.SPAWNER : Material.BARRIER;
        NamedTextColor spawnColor = mobSpawningEnabled ? NamedTextColor.GREEN : NamedTextColor.RED;
        inv.setItem(30, createGuiItem(spawnMat, Component.text("Mob Spawning: " + (mobSpawningEnabled ? "ON" : "OFF")).color(spawnColor),
                "Whether new mobs can", "naturally spawn.", "Click to toggle. (Server-wide)"));

        Material weatherMat = weatherCycleEnabled ? Material.WATER_BUCKET : Material.SUNFLOWER;
        NamedTextColor weatherColor = weatherCycleEnabled ? NamedTextColor.GREEN : NamedTextColor.RED;
        inv.setItem(31, createGuiItem(weatherMat, Component.text("Weather: " + (weatherCycleEnabled ? "ON" : "OFF")).color(weatherColor),
                "Whether rain/thunder can", "occur naturally.", "Turning OFF also clears", "any weather right away.",
                "Click to toggle. (Server-wide)"));

        Material smokeMat = lingeringSmokeGrenadesEnabled ? Material.LINGERING_POTION : Material.GLASS_BOTTLE;
        NamedTextColor smokeColor = lingeringSmokeGrenadesEnabled ? NamedTextColor.GREEN : NamedTextColor.RED;
        inv.setItem(32, createGuiItem(smokeMat, Component.text("Smoke Grenades: " + (lingeringSmokeGrenadesEnabled ? "ON" : "OFF")).color(smokeColor),
                "Lingering potions puff out poof", "particles while they linger,", "like a smoke grenade.",
                "Click to toggle."));

        inv.setItem(40, createGuiItem(Material.ARROW, Component.text("Back").color(NamedTextColor.GRAY)));
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

    // ---------- Kit data helpers (unlimited, admin-managed kits, locally tied to the editor) ----------

    /**
     * Returns the ordered list of kit ids owned by this editor, auto-creating
     * 3 blank kits the first time this player's kit library is touched.
     */
    private List<String> getKitIds(UUID ownerId) {
        List<String> ids = kitsDb.getStringList("playerkits." + ownerId + ".kitIds");
        if (ids == null || ids.isEmpty()) {
            ids = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                String id = "kit" + i;
                ids.add(id);
                initKitDefaults(ownerId, id, "Kit " + i);
            }
            kitsDb.set("playerkits." + ownerId + ".kitIds", ids);
            kitsDb.set("playerkits." + ownerId + ".nextKitSeq", 4);
            saveKitsDatabase();
        }
        return new ArrayList<>(ids);
    }

    private void initKitDefaults(UUID ownerId, String kitId, String name) {
        String base = "playerkits." + ownerId + ".kits." + kitId + ".";
        kitsDb.set(base + "name", name);
        kitsDb.set(base + "team", UNIVERSAL_TEAM);
        kitsDb.set(base + "maxPlayers", 0);
        kitsDb.set(base + "selectable", true);
    }

    private String createNewKit(UUID ownerId) {
        List<String> ids = getKitIds(ownerId);
        int seq = kitsDb.getInt("playerkits." + ownerId + ".nextKitSeq", ids.size() + 1);
        String id = "kit" + seq;
        ids.add(id);
        initKitDefaults(ownerId, id, "Kit " + ids.size());
        kitsDb.set("playerkits." + ownerId + ".kitIds", ids);
        kitsDb.set("playerkits." + ownerId + ".nextKitSeq", seq + 1);
        saveKitsDatabase();
        return id;
    }

    private String kitBase(UUID ownerId, String kitId) { return "playerkits." + ownerId + ".kits." + kitId + "."; }

    private String getKitName(UUID ownerId, String kitId) { return kitsDb.getString(kitBase(ownerId, kitId) + "name", kitId); }

    /**
     * Returns the item the admin dragged & dropped into the icon slot while
     * editing this kit (full ItemStack, so custom names/enchants/etc. carry
     * over), or a plain default if none has been set yet.
     */
    private ItemStack getKitIconItem(UUID ownerId, String kitId) {
        ItemStack stored = kitsDb.getItemStack(kitBase(ownerId, kitId) + "iconItem", null);
        if (stored != null) return stored.clone();
        return new ItemStack(Material.LEATHER_CHESTPLATE);
    }

    /** Clones the kit's icon item and stamps the kit's name/lore onto it for list/selection displays. */
    private ItemStack renderKitDisplayItem(ItemStack rawIcon, Component name, String... lore) {
        ItemStack item = (rawIcon != null ? rawIcon.clone() : new ItemStack(Material.LEATHER_CHESTPLATE));
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            List<Component> loreList = new ArrayList<>();
            for (String line : lore) {
                loreList.add(Component.text(line).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(loreList);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String getKitTeam(UUID ownerId, String kitId) { return kitsDb.getString(kitBase(ownerId, kitId) + "team", UNIVERSAL_TEAM); }

    private int getKitMaxPlayers(UUID ownerId, String kitId) { return kitsDb.getInt(kitBase(ownerId, kitId) + "maxPlayers", 0); }

    private boolean getKitSelectable(UUID ownerId, String kitId) { return kitsDb.getBoolean(kitBase(ownerId, kitId) + "selectable", true); }

    @SuppressWarnings("unchecked")
    private List<ItemStack> getKitContents(UUID ownerId, String kitId) {
        return (List<ItemStack>) kitsDb.getList(kitBase(ownerId, kitId) + "contents");
    }

    private boolean isKitBuilt(UUID ownerId, String kitId) {
        List<ItemStack> contents = getKitContents(ownerId, kitId);
        return contents != null && contents.size() == 54;
    }

    // ---------- Admin Kits list GUI (unlimited kits, paginated) ----------

    private void openAdminKitsGUI(Player player, int page) {
        UUID ownerId = player.getUniqueId();
        List<String> ids = getKitIds(ownerId);
        kitListPage.put(ownerId, page);

        Inventory inv = Bukkit.createInventory(null, 54, Component.text("Admin Kits").color(NamedTextColor.DARK_GRAY));
        String choice = kitsDb.getString("playerkits." + ownerId + ".choice", ids.isEmpty() ? null : ids.get(0));

        int perPage = 45;
        int start = page * perPage;
        int slot = 0;
        for (int i = start; i < ids.size() && slot < perPage; i++, slot++) {
            String kitId = ids.get(i);
            String team = getKitTeam(ownerId, kitId);
            int maxPlayers = getKitMaxPlayers(ownerId, kitId);
            boolean selectable = getKitSelectable(ownerId, kitId);

            List<String> lore = new ArrayList<>();
            lore.add("Team: " + (team.equals(UNIVERSAL_TEAM) ? "Universal" : team));
            lore.add("Max Players: " + (maxPlayers <= 0 ? "Unlimited" : String.valueOf(maxPlayers)));
            lore.add("Player Selection: " + (selectable ? "Enabled" : "Disabled"));
            lore.add(isKitBuilt(ownerId, kitId) ? "Contents: Built" : "Contents: Empty");
            if (kitId.equals(choice)) lore.add("SELECTED (your default kit)");
            lore.add("Left-click to select as default.");
            lore.add("Right-click to edit this kit.");

            ItemStack item = renderKitDisplayItem(getKitIconItem(ownerId, kitId), Component.text(getKitName(ownerId, kitId)).color(NamedTextColor.GOLD), lore.toArray(new String[0]));
            if (kitId.equals(choice)) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                    item.setItemMeta(meta);
                }
            }
            inv.setItem(slot, item);
        }

        if (page > 0) inv.setItem(45, createGuiItem(Material.ARROW, Component.text("Previous Page").color(NamedTextColor.GRAY)));
        inv.setItem(46, createGuiItem(Material.NETHER_STAR, Component.text("Add New Kit").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD), "Creates a new blank kit."));

        Material phaseMat = kitSelectionPhaseEnabled ? Material.LIME_DYE : Material.GRAY_DYE;
        NamedTextColor phaseColor = kitSelectionPhaseEnabled ? NamedTextColor.GREEN : NamedTextColor.RED;
        inv.setItem(49, createGuiItem(phaseMat, Component.text("Kit Selection Phase: " + (kitSelectionPhaseEnabled ? "ON" : "OFF")).color(phaseColor),
                "If ON, players choose from admin-", "approved kits during the inventory", "management phase before each match.", "Click to toggle."));

        if (start + perPage < ids.size()) inv.setItem(52, createGuiItem(Material.ARROW, Component.text("Next Page").color(NamedTextColor.GRAY)));
        inv.setItem(53, createGuiItem(Material.ARROW, Component.text("Back").color(NamedTextColor.GRAY)));
        player.openInventory(inv);
    }

    // ---------- Kit editor GUIs (double-chest main editor + potions/toggles sub-screens) ----------

    /** Returns item.clone(), or null if the slot is empty or still showing its placeholder. */
    private ItemStack plainOrNull(ItemStack item, Material placeholder) {
        if (item == null || item.getType() == placeholder) return null;
        return item.clone();
    }

    /** Begins (or resumes) editing a kit: backs up the admin's real inventory/gamemode once, loads the saved kit into the in-progress staging buffer, and opens the main editor. */
    private void beginEditingKit(Player player, String kitId) {
        UUID ownerId = player.getUniqueId();
        if (player.getGameMode() != GameMode.CREATIVE) {
            savedKitBuilderInventories.put(ownerId, player.getInventory().getContents());
            savedKitBuilderGameModes.put(ownerId, player.getGameMode());
            player.getInventory().clear();
            player.setGameMode(GameMode.CREATIVE);
        }
        editingKitId.put(ownerId, kitId);

        ItemStack[] stage = new ItemStack[54];
        List<ItemStack> existing = getKitContents(ownerId, kitId);
        if (existing != null && existing.size() == 54) {
            for (int i = 0; i < 54; i++) stage[i] = existing.get(i);
        }
        kitBuilderStage.put(ownerId, stage);
        kitBuilderIcon.put(ownerId, kitsDb.getItemStack(kitBase(ownerId, kitId) + "iconItem", null));

        openMasterKitEditorGUI(player);
    }

    /**
     * Main kit editor: a double-chest (54 slot) layout with 5 armor/offhand
     * slots, a drag-and-drop icon slot, buttons to the potions and toggles
     * sub-screens, a save button, and 36 free slots representing the kit's
     * full inventory (main storage + hotbar, in that order).
     */
    private void openMasterKitEditorGUI(Player player) {
        UUID ownerId = player.getUniqueId();
        String kitId = editingKitId.get(ownerId);
        ItemStack[] stage = kitBuilderStage.computeIfAbsent(ownerId, k -> new ItemStack[54]);
        ItemStack icon = kitBuilderIcon.get(ownerId);

        Inventory inv = Bukkit.createInventory(null, 54, Component.text("Master Kit Creator - " + getKitName(ownerId, kitId)).color(NamedTextColor.DARK_GRAY));

        inv.setItem(0, stage[0] != null ? stage[0] : createGuiItem(HELMET_PLACEHOLDER, Component.text("Helmet Slot").color(NamedTextColor.RED)));
        inv.setItem(1, stage[1] != null ? stage[1] : createGuiItem(CHESTPLATE_PLACEHOLDER, Component.text("Chestplate Slot").color(NamedTextColor.RED)));
        inv.setItem(2, stage[2] != null ? stage[2] : createGuiItem(LEGGINGS_PLACEHOLDER, Component.text("Leggings Slot").color(NamedTextColor.RED)));
        inv.setItem(3, stage[3] != null ? stage[3] : createGuiItem(BOOTS_PLACEHOLDER, Component.text("Boots Slot").color(NamedTextColor.RED)));
        inv.setItem(4, stage[4] != null ? stage[4] : createGuiItem(OFFHAND_PLACEHOLDER, Component.text("Offhand Slot").color(NamedTextColor.BLUE)));

        inv.setItem(5, icon != null ? icon.clone() : createGuiItem(ICON_PLACEHOLDER, Component.text("Kit Icon").color(NamedTextColor.YELLOW),
                "Drag & drop any item here", "to use it as this kit's icon."));

        inv.setItem(6, createGuiItem(Material.POTION, Component.text("Potion Effects").color(NamedTextColor.LIGHT_PURPLE), "Click to open the potion effects editor."));
        inv.setItem(7, createGuiItem(Material.COMPARATOR, Component.text("Kit Toggles").color(NamedTextColor.YELLOW), "Rename, team, max players,", "and player selection.", "Click to open."));
        inv.setItem(8, createGuiItem(Material.EMERALD_BLOCK, Component.text("SAVE KIT CONTENTS").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD)));

        for (int i = 0; i < 36; i++) {
            inv.setItem(9 + i, stage[18 + i]);
        }

        for (int i = 45; i < 53; i++) {
            inv.setItem(i, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" ")));
        }
        inv.setItem(53, createGuiItem(Material.ARROW, Component.text("Back").color(NamedTextColor.GRAY)));

        player.openInventory(inv);
    }

    /** Sub-screen for the 12 potion-effect slots (unchanged behavior from before: drop a potion item in to grant its effect(s)). */
    private void openKitPotionsGUI(Player player) {
        UUID ownerId = player.getUniqueId();
        ItemStack[] stage = kitBuilderStage.computeIfAbsent(ownerId, k -> new ItemStack[54]);

        Inventory inv = Bukkit.createInventory(null, 27, Component.text("Kit Potion Effects").color(NamedTextColor.DARK_GRAY));

        for (int i = 0; i < 12; i++) {
            inv.setItem(i, stage[5 + i] != null ? stage[5 + i] : createGuiItem(POTION_PLACEHOLDER, Component.text("Potion Effect").color(NamedTextColor.LIGHT_PURPLE),
                    "Drag a potion here to grant", "its effect(s) when this kit is given."));
        }
        for (int i = 12; i < 18; i++) {
            inv.setItem(i, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" ")));
        }
        inv.setItem(22, createGuiItem(Material.ARROW, Component.text("Back").color(NamedTextColor.GRAY)));

        player.openInventory(inv);
    }

    /** Sub-screen for kit metadata: rename, team, max players, and player selection. */
    private void openKitTogglesGUI(Player player) {
        UUID ownerId = player.getUniqueId();
        String kitId = editingKitId.get(ownerId);

        Inventory inv = Bukkit.createInventory(null, 27, Component.text("Kit Toggles").color(NamedTextColor.DARK_GRAY));

        inv.setItem(10, createGuiItem(Material.FEATHER, Component.text("Rename").color(NamedTextColor.AQUA),
                "Name: " + getKitName(ownerId, kitId), "Click to type a new name in chat."));

        String team = getKitTeam(ownerId, kitId);
        Material teamMat = team.equals(UNIVERSAL_TEAM) ? Material.WHITE_WOOL : EventTeam.valueOf(team).getWool();
        NamedTextColor teamColor = team.equals(UNIVERSAL_TEAM) ? NamedTextColor.WHITE : EventTeam.valueOf(team).getScoreboardColor();
        inv.setItem(12, createGuiItem(teamMat, Component.text("Team: " + (team.equals(UNIVERSAL_TEAM) ? "Universal" : team)).color(teamColor),
                "Click to cycle: Universal -> Red -> Blue", "-> Green -> Yellow -> Universal."));

        int maxPlayers = getKitMaxPlayers(ownerId, kitId);
        inv.setItem(14, createGuiItem(Material.HOPPER, Component.text("Max Players: " + (maxPlayers <= 0 ? "Unlimited" : String.valueOf(maxPlayers))).color(NamedTextColor.YELLOW),
                "Caps how many players may pick this", "kit during the Kit Selection Phase.",
                "Left-click: +1  Right-click: -1  (0 = Unlimited)"));

        boolean selectable = getKitSelectable(ownerId, kitId);
        Material selMat = selectable ? Material.LIME_DYE : Material.GRAY_DYE;
        NamedTextColor selColor = selectable ? NamedTextColor.GREEN : NamedTextColor.RED;
        inv.setItem(16, createGuiItem(selMat, Component.text("Player Selection: " + (selectable ? "Enabled" : "Disabled")).color(selColor),
                "Whether this kit appears in the", "in-match Kit Selection Phase menu.", "Click to toggle."));

        inv.setItem(22, createGuiItem(Material.ARROW, Component.text("Back").color(NamedTextColor.GRAY)));

        player.openInventory(inv);
    }

    /** Snapshots the main editor's armor/icon/inventory slots into the staging buffer before navigating away. */
    private void captureMasterEditor(Player player, Inventory inv) {
        UUID ownerId = player.getUniqueId();
        ItemStack[] stage = kitBuilderStage.computeIfAbsent(ownerId, k -> new ItemStack[54]);
        stage[0] = plainOrNull(inv.getItem(0), HELMET_PLACEHOLDER);
        stage[1] = plainOrNull(inv.getItem(1), CHESTPLATE_PLACEHOLDER);
        stage[2] = plainOrNull(inv.getItem(2), LEGGINGS_PLACEHOLDER);
        stage[3] = plainOrNull(inv.getItem(3), BOOTS_PLACEHOLDER);
        stage[4] = plainOrNull(inv.getItem(4), OFFHAND_PLACEHOLDER);
        kitBuilderIcon.put(ownerId, plainOrNull(inv.getItem(5), ICON_PLACEHOLDER));
        for (int i = 0; i < 36; i++) {
            stage[18 + i] = inv.getItem(9 + i);
        }
    }

    /** Snapshots the potions sub-screen's 12 slots into the staging buffer before navigating away. */
    private void capturePotionsEditor(Player player, Inventory inv) {
        UUID ownerId = player.getUniqueId();
        ItemStack[] stage = kitBuilderStage.computeIfAbsent(ownerId, k -> new ItemStack[54]);
        for (int i = 0; i < 12; i++) {
            stage[5 + i] = plainOrNull(inv.getItem(i), POTION_PLACEHOLDER);
        }
    }

    /** Persists the currently-open main editor's contents (plus whatever's staged for potions) as the kit's saved contents. Sends exactly one confirmation message. */
    private void saveKitFromGUI(Player player, Inventory gui, String kitId) {
        UUID ownerId = player.getUniqueId();
        ItemStack[] stage = kitBuilderStage.computeIfAbsent(ownerId, k -> new ItemStack[54]);

        List<ItemStack> kitToSave = new ArrayList<>(Collections.nCopies(54, null));
        kitToSave.set(0, plainOrNull(gui.getItem(0), HELMET_PLACEHOLDER));
        kitToSave.set(1, plainOrNull(gui.getItem(1), CHESTPLATE_PLACEHOLDER));
        kitToSave.set(2, plainOrNull(gui.getItem(2), LEGGINGS_PLACEHOLDER));
        kitToSave.set(3, plainOrNull(gui.getItem(3), BOOTS_PLACEHOLDER));
        kitToSave.set(4, plainOrNull(gui.getItem(4), OFFHAND_PLACEHOLDER));
        for (int i = 0; i < 12; i++) kitToSave.set(5 + i, stage[5 + i]);
        for (int i = 0; i < 36; i++) kitToSave.set(18 + i, gui.getItem(9 + i));

        ItemStack iconItem = plainOrNull(gui.getItem(5), ICON_PLACEHOLDER);

        kitsDb.set(kitBase(ownerId, kitId) + "contents", kitToSave);
        kitsDb.set(kitBase(ownerId, kitId) + "iconItem", iconItem);
        saveKitsDatabase();
        player.sendMessage(Component.text(getKitName(ownerId, kitId) + " saved!").color(NamedTextColor.GREEN));
    }

    private String cycleKitTeam(String current) {
        List<String> order = List.of(UNIVERSAL_TEAM, "RED", "BLUE", "GREEN", "YELLOW");
        int idx = order.indexOf(current);
        return order.get((idx + 1) % order.size());
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
            else if (e.getRawSlot() == 14) openAdminKitsGUI(player, 0);
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
                inventoryManagementSeconds = cycleInventoryManagementTime(inventoryManagementSeconds);
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
            } else if (e.getRawSlot() == 23) {
                maxLives = cycleLives(maxLives, e.getClick().isRightClick() ? -1 : 1);
                getConfig().set("settings.max-lives", maxLives);
                saveConfig();
                openGameOptionsGUI(player);
            } else if (e.getRawSlot() == 28) {
                mobGriefingEnabled = !mobGriefingEnabled;
                getConfig().set("settings.mob-griefing", mobGriefingEnabled);
                saveConfig();
                applyGameRule(GameRule.MOB_GRIEFING, mobGriefingEnabled);
                openGameOptionsGUI(player);
            } else if (e.getRawSlot() == 29) {
                daylightCycleEnabled = !daylightCycleEnabled;
                getConfig().set("settings.daylight-cycle", daylightCycleEnabled);
                saveConfig();
                applyGameRule(GameRule.DO_DAYLIGHT_CYCLE, daylightCycleEnabled);
                openGameOptionsGUI(player);
            } else if (e.getRawSlot() == 30) {
                mobSpawningEnabled = !mobSpawningEnabled;
                getConfig().set("settings.mob-spawning", mobSpawningEnabled);
                saveConfig();
                applyGameRule(GameRule.DO_MOB_SPAWNING, mobSpawningEnabled);
                openGameOptionsGUI(player);
            } else if (e.getRawSlot() == 31) {
                weatherCycleEnabled = !weatherCycleEnabled;
                getConfig().set("settings.weather-cycle", weatherCycleEnabled);
                saveConfig();
                applyGameRule(GameRule.DO_WEATHER_CYCLE, weatherCycleEnabled);
                if (!weatherCycleEnabled) {
                    for (World world : Bukkit.getWorlds()) {
                        world.setStorm(false);
                        world.setThundering(false);
                    }
                }
                openGameOptionsGUI(player);
            } else if (e.getRawSlot() == 32) {
                lingeringSmokeGrenadesEnabled = !lingeringSmokeGrenadesEnabled;
                getConfig().set("settings.lingering-smoke-grenades", lingeringSmokeGrenadesEnabled);
                saveConfig();
                openGameOptionsGUI(player);
            } else if (e.getRawSlot() == 40) {
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
            UUID ownerId = player.getUniqueId();
            String kitId = editingKitId.get(ownerId);
            int slot = e.getRawSlot();

            if (slot == 0 || slot == 1 || slot == 2 || slot == 3 || slot == 4 || slot == 5) {
                Material placeholder = switch (slot) {
                    case 0 -> HELMET_PLACEHOLDER;
                    case 1 -> CHESTPLATE_PLACEHOLDER;
                    case 2 -> LEGGINGS_PLACEHOLDER;
                    case 3 -> BOOTS_PLACEHOLDER;
                    case 4 -> OFFHAND_PLACEHOLDER;
                    default -> ICON_PLACEHOLDER;
                };
                ItemStack clicked = e.getCurrentItem();
                if (clicked != null && clicked.getType() == placeholder) {
                    e.setCancelled(true);
                    ItemStack cursorItem = e.getCursor();
                    if (cursorItem != null && cursorItem.getType() != Material.AIR) {
                        e.setCurrentItem(cursorItem.clone());
                        e.getView().setCursor(null);
                    }
                }
                return;
            }

            if (slot == 6) {
                e.setCancelled(true);
                if (kitId == null) return;
                captureMasterEditor(player, e.getInventory());
                kitEditorInternalNav.add(ownerId);
                openKitPotionsGUI(player);
            } else if (slot == 7) {
                e.setCancelled(true);
                if (kitId == null) return;
                captureMasterEditor(player, e.getInventory());
                kitEditorInternalNav.add(ownerId);
                openKitTogglesGUI(player);
            } else if (slot == 8) {
                e.setCancelled(true);
                if (kitId == null) return;
                saveKitFromGUI(player, e.getInventory(), kitId);
                player.closeInventory();
                Bukkit.getScheduler().runTask(this, () -> openAdminKitsGUI(player, kitListPage.getOrDefault(ownerId, 0)));
            } else if (slot == 53) {
                e.setCancelled(true);
                player.closeInventory();
                Bukkit.getScheduler().runTask(this, () -> openAdminKitsGUI(player, kitListPage.getOrDefault(ownerId, 0)));
            } else if (slot >= 45 && slot < 53) {
                e.setCancelled(true);
            }
            // slots 9-44 (the 36-slot inventory area): left uncancelled for free drag-and-drop editing.
        }
        else if (title.equals("Kit Potion Effects")) {
            UUID ownerId = player.getUniqueId();
            String kitId = editingKitId.get(ownerId);
            int slot = e.getRawSlot();

            if (slot >= 0 && slot < 12) {
                ItemStack clicked = e.getCurrentItem();
                if (clicked != null && clicked.getType() == POTION_PLACEHOLDER) {
                    e.setCancelled(true);
                    ItemStack cursorItem = e.getCursor();
                    if (cursorItem != null && cursorItem.getType() != Material.AIR) {
                        e.setCurrentItem(cursorItem.clone());
                        e.getView().setCursor(null);
                    }
                }
                return;
            }

            if (slot == 22) {
                e.setCancelled(true);
                if (kitId == null) return;
                capturePotionsEditor(player, e.getInventory());
                kitEditorInternalNav.add(ownerId);
                openMasterKitEditorGUI(player);
            } else if (slot >= 12 && slot < 27) {
                e.setCancelled(true);
            }
        }
        else if (title.equals("Kit Toggles")) {
            e.setCancelled(true);
            UUID ownerId = player.getUniqueId();
            String kitId = editingKitId.get(ownerId);
            if (kitId == null) return;
            int slot = e.getRawSlot();

            if (slot == 10) {
                kitEditorInternalNav.add(ownerId);
                awaitingKitNameInput.put(ownerId, kitId);
                player.closeInventory();
                player.sendMessage(Component.text("Type the new name for this kit in chat (or type 'cancel').").color(NamedTextColor.AQUA));
            } else if (slot == 12) {
                String newTeam = cycleKitTeam(getKitTeam(ownerId, kitId));
                kitsDb.set(kitBase(ownerId, kitId) + "team", newTeam);
                saveKitsDatabase();
                openKitTogglesGUI(player);
            } else if (slot == 14) {
                int current = getKitMaxPlayers(ownerId, kitId);
                int updated = current + (e.getClick().isRightClick() ? -1 : 1);
                if (updated < 0) updated = 0;
                if (updated > 64) updated = 64;
                kitsDb.set(kitBase(ownerId, kitId) + "maxPlayers", updated);
                saveKitsDatabase();
                openKitTogglesGUI(player);
            } else if (slot == 16) {
                boolean updated = !getKitSelectable(ownerId, kitId);
                kitsDb.set(kitBase(ownerId, kitId) + "selectable", updated);
                saveKitsDatabase();
                openKitTogglesGUI(player);
            } else if (slot == 22) {
                kitEditorInternalNav.add(ownerId);
                openMasterKitEditorGUI(player);
            }
        }
        else if (title.equals("Admin Kits")) {
            e.setCancelled(true);
            UUID ownerId = player.getUniqueId();
            int page = kitListPage.getOrDefault(ownerId, 0);
            List<String> ids = getKitIds(ownerId);

            if (e.getRawSlot() == 45 && page > 0) {
                openAdminKitsGUI(player, page - 1);
            } else if (e.getRawSlot() == 46) {
                String newId = createNewKit(ownerId);
                player.sendMessage(Component.text("Created " + getKitName(ownerId, newId) + "!").color(NamedTextColor.GREEN));
                openAdminKitsGUI(player, page);
            } else if (e.getRawSlot() == 49) {
                kitSelectionPhaseEnabled = !kitSelectionPhaseEnabled;
                getConfig().set("settings.kit-selection-phase", kitSelectionPhaseEnabled);
                if (kitSelectionPhaseEnabled && inventoryManagementSeconds <= 0) {
                    inventoryManagementSeconds = 5;
                    getConfig().set("settings.inventory-management-time", inventoryManagementSeconds);
                }
                saveConfig();
                openAdminKitsGUI(player, page);
            } else if (e.getRawSlot() == 52) {
                openAdminKitsGUI(player, page + 1);
            } else if (e.getRawSlot() == 53) {
                openMainGUI(player);
            } else if (e.getRawSlot() >= 0 && e.getRawSlot() < 45) {
                int index = page * 45 + e.getRawSlot();
                if (index < ids.size()) {
                    String kitId = ids.get(index);
                    if (e.getClick().isRightClick()) {
                        beginEditingKit(player, kitId);
                    } else {
                        kitsDb.set("playerkits." + ownerId + ".choice", kitId);
                        saveKitsDatabase();
                        player.sendMessage(Component.text("You selected " + getKitName(ownerId, kitId) + " as your default kit!").color(NamedTextColor.GREEN));
                        openAdminKitsGUI(player, page);
                    }
                }
            }
        }
        else if (title.equals("Choose Your Kit")) {
            e.setCancelled(true);
            handleKitSelectionClick(player, e.getRawSlot());
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

        boolean isKitEditorScreen = title.startsWith("Master Kit Creator") || title.equals("Kit Potion Effects") || title.equals("Kit Toggles");
        if (isKitEditorScreen) {
            UUID ownerId = player.getUniqueId();
            if (!kitEditorInternalNav.remove(ownerId) && savedKitBuilderInventories.containsKey(ownerId)) {
                // Truly leaving kit editing (not just hopping to a sub-screen): restore the
                // admin's real inventory/gamemode and discard any in-progress kit builder state.
                player.setGameMode(savedKitBuilderGameModes.get(ownerId));
                player.getInventory().setContents(savedKitBuilderInventories.get(ownerId));
                savedKitBuilderInventories.remove(ownerId);
                savedKitBuilderGameModes.remove(ownerId);
                kitBuilderStage.remove(ownerId);
                kitBuilderIcon.remove(ownerId);
                editingKitId.remove(ownerId);
            }
        }

        if (title.equals("Choose Your Kit")) {
            UUID id = player.getUniqueId();
            kitSelectionGuiOpenPlayers.remove(id);

            // If the selection phase is still actively running for this player (they're
            // still frozen/invincible in it, haven't been given a kit yet), closing the
            // GUI (Esc, clicking off, etc.) should not let them dodge picking a kit -
            // pop it back open next tick. Once the phase actually ends for them,
            // frozenPlayers no longer contains their id, so this naturally stops.
            if (kitSelectionPhaseActive && eventRunning && isParticipant(id) && frozenPlayers.contains(id)) {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (kitSelectionPhaseActive && eventRunning && isParticipant(id) && frozenPlayers.contains(id)
                            && player.isOnline()) {
                        openKitSelectionGUI(player);
                    }
                });
            }
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
     * inside blocks where they would immediately suffocate or take damage.
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

    private UUID currentKitOwnerId() {
        return eventHostId != null ? eventHostId : null;
    }

    /**
     * Tries the chosen kit id first, then cycles through the owner's other
     * kit ids (in list order) looking for the first one that has actually
     * been built (54 saved slots), so nobody ends up empty-handed just
     * because the preferred kit hasn't been designed yet.
     */
    private List<ItemStack> resolveKitContentsById(UUID kitOwnerId, String preferredKitId) {
        List<String> ids = getKitIds(kitOwnerId);
        if (ids.isEmpty()) return null;

        if (preferredKitId != null && isKitBuilt(kitOwnerId, preferredKitId)) {
            return getKitContents(kitOwnerId, preferredKitId);
        }
        for (String id : ids) {
            if (isKitBuilt(kitOwnerId, id)) return getKitContents(kitOwnerId, id);
        }
        return null;
    }

    /** Returns the kit id actually used (for tracking/respawns), or null if nothing was built. */
    private String resolvedKitId(UUID kitOwnerId, String preferredKitId) {
        List<String> ids = getKitIds(kitOwnerId);
        if (preferredKitId != null && isKitBuilt(kitOwnerId, preferredKitId)) return preferredKitId;
        for (String id : ids) {
            if (isKitBuilt(kitOwnerId, id)) return id;
        }
        return null;
    }

    /**
     * Everyone in the match uses the host's kit library - whoever clicked
     * "Start" (eventHostId) - EXCEPT the host themself, who of course still
     * uses their own library. Players still build kits in "Admin Kits";
     * that library only actually gets used when they are hosting (or,
     * during a Kit Selection Phase, when the host approves specific
     * kits for everyone to pick from).
     */
    private void giveKit(Player player) {
        UUID kitOwnerId = (eventHostId != null) ? eventHostId : player.getUniqueId();
        String choice = kitsDb.getString("playerkits." + kitOwnerId + ".choice", null);
        giveKitById(player, kitOwnerId, choice);
    }

    private void giveKitById(Player player, UUID kitOwnerId, String preferredKitId) {
        String resolvedId = resolvedKitId(kitOwnerId, preferredKitId);
        List<ItemStack> kitContents = resolveKitContentsById(kitOwnerId, preferredKitId);
        assignedKitId.put(player.getUniqueId(), resolvedId);

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

    // ==========================================
    // Player Kit Selection Phase
    // ==========================================

    /** Kits from the host's library that are admin-approved and available to this participant. */
    private List<String> getApprovedKitsFor(UUID kitOwnerId, Player participant) {
        List<String> result = new ArrayList<>();
        List<String> ids = getKitIds(kitOwnerId);
        EventTeam team = playerTeams.get(participant.getUniqueId());

        for (String id : ids) {
            if (!getKitSelectable(kitOwnerId, id)) continue;
            String kitTeam = getKitTeam(kitOwnerId, id);
            boolean teamMatches = currentMode == EventMode.FFA
                    || kitTeam.equals(UNIVERSAL_TEAM)
                    || (team != null && kitTeam.equals(team.name()));
            if (teamMatches) result.add(id);
        }
        return result;
    }

    /** How many current match participants have this kit selected right now. */
    private int countKitSelections(String kitId) {
        int count = 0;
        for (String chosen : kitSelectionChoice.values()) {
            if (kitId.equals(chosen)) count++;
        }
        return count;
    }

    private boolean kitSelectionPhaseApplies() {
        if (!kitSelectionPhaseEnabled) return false;
        UUID kitOwnerId = currentKitOwnerId();
        if (kitOwnerId == null) return false;
        for (String id : getKitIds(kitOwnerId)) {
            if (getKitSelectable(kitOwnerId, id)) return true;
        }
        return false;
    }

    /**
     * Freezes/protects participants (same as the plain inventory-management
     * window) and opens each of them the "Choose Your Kit" GUI, filtered to
     * admin-approved kits for their team (or all approved kits in FFA).
     * Teammates can see each other's live picks via refreshKitSelectionGuis().
     * When the timer expires, everyone receives their chosen kit (or the
     * first available approved kit if they never picked one).
     */
    private void beginKitSelectionPhase(List<Player> participants) {
        kitSelectionPhaseActive = true;
        UUID kitOwnerId = currentKitOwnerId();

        for (Player p : participants) {
            UUID id = p.getUniqueId();
            frozenPlayers.add(id);
            invinciblePlayers.add(id);
            kitSelectionChoice.remove(id);
            p.showTitle(Title.title(
                    Component.text("Choose Your Kit!").color(NamedTextColor.AQUA).decorate(TextDecoration.BOLD),
                    Component.text("The match begins in " + inventoryManagementSeconds + " seconds...").color(NamedTextColor.GRAY)));
            openKitSelectionGUI(p);
        }

        kitSelectionTimerTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Player p : participants) {
                UUID id = p.getUniqueId();
                frozenPlayers.remove(id);
                invinciblePlayers.remove(id);
                kitSelectionGuiOpenPlayers.remove(id);
                p.closeInventory();

                String chosen = kitSelectionChoice.get(id);
                if (chosen == null) {
                    List<String> approved = getApprovedKitsFor(kitOwnerId != null ? kitOwnerId : id, p);
                    if (!approved.isEmpty()) chosen = approved.get(0);
                }
                giveKitById(p, kitOwnerId != null ? kitOwnerId : id, chosen);

                p.sendMessage(Component.text("\n\n★ THE EVENT HAS STARTED! ★\n\n").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
                p.showTitle(Title.title(Component.text("FIGHT!").color(NamedTextColor.RED).decorate(TextDecoration.BOLD), Component.empty()));
                grantInvincibility(p, spawnInvincibilitySeconds);
            }
            kitSelectionChoice.clear();
            kitSelectionPhaseActive = false;
            kitSelectionTimerTask = null;
        }, inventoryManagementSeconds * 20L);
    }

    private void openKitSelectionGUI(Player player) {
        UUID kitOwnerId = currentKitOwnerId();
        if (kitOwnerId == null) kitOwnerId = player.getUniqueId();
        List<String> approved = getApprovedKitsFor(kitOwnerId, player);

        Inventory inv = Bukkit.createInventory(null, 54, Component.text("Choose Your Kit").color(NamedTextColor.DARK_GRAY));
        String myChoice = kitSelectionChoice.get(player.getUniqueId());
        EventTeam myTeam = playerTeams.get(player.getUniqueId());

        int slot = 0;
        for (String id : approved) {
            if (slot >= 45) break;
            UUID finalOwner = kitOwnerId;
            int selectedCount = countKitSelections(id);
            int maxPlayers = getKitMaxPlayers(finalOwner, id);
            boolean full = maxPlayers > 0 && selectedCount >= maxPlayers && !id.equals(myChoice);

            List<String> lore = new ArrayList<>();
            lore.add("Team: " + (getKitTeam(finalOwner, id).equals(UNIVERSAL_TEAM) ? "Universal" : getKitTeam(finalOwner, id)));
            lore.add("Slots: " + (maxPlayers <= 0 ? (selectedCount + " picking / Unlimited") : (selectedCount + " / " + maxPlayers)));

            List<String> teammatePicks = new ArrayList<>();
            for (Map.Entry<UUID, String> entry : kitSelectionChoice.entrySet()) {
                if (!id.equals(entry.getValue())) continue;
                if (currentMode == EventMode.TEAMS && myTeam != null && playerTeams.get(entry.getKey()) != myTeam) continue;
                Player teammate = Bukkit.getPlayer(entry.getKey());
                if (teammate != null) teammatePicks.add(teammate.getName());
            }
            if (!teammatePicks.isEmpty()) lore.add("Picking: " + String.join(", ", teammatePicks));

            if (id.equals(myChoice)) lore.add("SELECTED");
            else if (full) lore.add("FULL");
            else lore.add("Click to select this kit.");

            ItemStack item = renderKitDisplayItem(getKitIconItem(finalOwner, id), Component.text(getKitName(finalOwner, id)).color(full ? NamedTextColor.DARK_GRAY : NamedTextColor.GOLD), lore.toArray(new String[0]));
            if (id.equals(myChoice)) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                    item.setItemMeta(meta);
                }
            }
            inv.setItem(slot++, item);
        }

        player.openInventory(inv);
        kitSelectionGuiOpenPlayers.add(player.getUniqueId());
    }

    private void handleKitSelectionClick(Player player, int rawSlot) {
        UUID kitOwnerId = currentKitOwnerId();
        if (kitOwnerId == null) kitOwnerId = player.getUniqueId();
        List<String> approved = getApprovedKitsFor(kitOwnerId, player);
        if (rawSlot < 0 || rawSlot >= approved.size()) return;

        String kitId = approved.get(rawSlot);
        int maxPlayers = getKitMaxPlayers(kitOwnerId, kitId);
        int selectedCount = countKitSelections(kitId);
        boolean alreadyMine = kitId.equals(kitSelectionChoice.get(player.getUniqueId()));

        if (!alreadyMine && maxPlayers > 0 && selectedCount >= maxPlayers) {
            player.sendMessage(Component.text("That kit is full!").color(NamedTextColor.RED));
            return;
        }

        kitSelectionChoice.put(player.getUniqueId(), kitId);
        player.sendMessage(Component.text("You are now picking: " + getKitName(kitOwnerId, kitId)).color(NamedTextColor.GREEN));
        refreshKitSelectionGuis();
    }

    /** Live-refreshes the "Choose Your Kit" GUI for every participant who currently has it open. */
    private void refreshKitSelectionGuis() {
        for (UUID id : new ArrayList<>(kitSelectionGuiOpenPlayers)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) openKitSelectionGUI(p);
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();

        if (awaitingKitNameInput.containsKey(id)) {
            e.setCancelled(true);
            String kitId = awaitingKitNameInput.remove(id);
            String typed = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
            Player player = e.getPlayer();

            Bukkit.getScheduler().runTask(this, () -> {
                if (!typed.equalsIgnoreCase("cancel") && !typed.isEmpty()) {
                    String finalName = typed.length() > 32 ? typed.substring(0, 32) : typed;
                    kitsDb.set(kitBase(id, kitId) + "name", finalName);
                    saveKitsDatabase();
                    player.sendMessage(Component.text("Kit renamed to \"" + finalName + "\"!").color(NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Component.text("Rename cancelled.").color(NamedTextColor.YELLOW));
                }
                openKitTogglesGUI(player);
            });
            return;
        }

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