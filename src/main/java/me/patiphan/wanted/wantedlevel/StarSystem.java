package me.patiphan.wanted.wantedlevel;

import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import net.milkbowl.vault.economy.Economy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class StarSystem extends JavaPlugin implements Listener {

    private Map<String, Integer> starLevels = new ConcurrentHashMap<>();
    private Map<String, Integer> killCounts = new ConcurrentHashMap<>();
    private Map<Integer, Double> lossPercentages = new ConcurrentHashMap<>();
    private Map<Integer, Long> cooldownTimes = new ConcurrentHashMap<>();
    private Map<String, BukkitRunnable> cooldownTasks = new ConcurrentHashMap<>();
    private Map<String, Boolean> isLevel5Cooldown = new ConcurrentHashMap<>(); // ประกาศตัวแปรนี้

    private Economy economy;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        // Setup Vault Economy
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            economy = getServer().getServicesManager().getRegistration(Economy.class).getProvider();
            if (economy != null) {
                getLogger().info("Vault Economy connected successfully");
            } else {
                getLogger().warning("Failed to connect to Vault Economy");
            }
        } else {
            getLogger().warning("Vault plugin not found");
        }

        // Check for PlaceholderAPI and register the placeholder
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new StarSystemPlaceholder(this).register();
            getLogger().info("StarSystem Placeholder registered with PlaceholderAPI");
        } else {
            getLogger().warning("PlaceholderAPI plugin not found. Placeholder features will be disabled.");
        }

        getLogger().info("StarSystem plugin enabled");

        getCommand("wanted").setExecutor(new CommandHandler());

        // Load percentages and messages from config
        saveDefaultConfig();
        loadLossPercentages();
        loadCooldownTimes();
    }

    @Override
    public void onDisable() {
        getLogger().info("StarSystem plugin disabled");

        // ยกเลิกงานคูลดาวน์ทั้งหมดเมื่อปิดปลั๊กอิน
        for (BukkitRunnable task : cooldownTasks.values()) {
            task.cancel();
        }
        cooldownTasks.clear();
    }

    private void loadLossPercentages() {
        for (int i = 1; i <= 5; i++) {
            double percentage = getConfig().getDouble("loss_percentages." + i, 0.0);
            lossPercentages.put(i, percentage);
        }
    }

    private void loadCooldownTimes() {
        for (int i = 1; i <= 5; i++) {
            long time = getConfig().getLong("cooldown_times." + i, 20);
            cooldownTimes.put(i, time);
        }
    }

    public int getStarLevel(String playerName) {
        return starLevels.getOrDefault(playerName, 0);
    }

    private double calculateMoneyLost(Player player) {
        if (economy == null) {
            getLogger().warning("Economy not initialized for player " + player.getName());
            return 0.0;
        }
        int level = getStarLevel(player.getName());
        double percentage = lossPercentages.getOrDefault(level, 0.0);
        double currentBalance = economy.getBalance(player);
        return currentBalance * (percentage / 100.0);
    }

    // Function to spawn MysticMobs in front of the player based on their star level
    private void spawnMysticMobsForPlayer(Player player, int starLevel) {
        if (!MythicBukkit.inst().isEnabled()) {
            getLogger().warning("MythicMobs API not initialized. Cannot spawn mobs.");
            return;
        }

        // Determine the number of mobs to spawn based on star level
        int mobsToSpawn = getConfig().getInt("mysticmobs_spawn.star_" + starLevel, 0);

        for (int i = 0; i < mobsToSpawn; i++) {
            // Spawn a custom MythicMob named "Policeman"
            Location spawnLocation = player.getLocation().add(2, 0, 2);
            ActiveMob mob = MythicBukkit.inst().getMobManager().spawnMob("Policeman", spawnLocation);

            // Handle the spawned mob if it exists
            if (mob != null) {
                getLogger().info("Spawned a Policeman for player " + player.getName());
            } else {
                getLogger().warning("Failed to spawn a Policeman for player " + player.getName());
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        // Reset star level to 0 on death
        String victimName = victim.getName();
        resetStarLevelAndCancelMobs(victimName);

        // Reduce money from victim if they have stars
        double moneyLost = calculateMoneyLost(victim);
        if (moneyLost > 0) {
            double currentBalance = economy.getBalance(victim);
            if (currentBalance >= moneyLost) {
                economy.withdrawPlayer(victim, moneyLost);
                victim.sendMessage("§cคุณเสียเงินจำนวน " + moneyLost + " เนื่องจากการถูกฆ่า.");
            } else {
                economy.withdrawPlayer(victim, currentBalance);
                victim.sendMessage("§cคุณเสียเงินทั้งหมดที่มีเนื่องจากการถูกฆ่า.");
            }
        }

        // Handle case where the player is killed by another player
        if (killer != null) {
            handleKill(killer);
        }
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() instanceof Player) {
            Player killer = event.getEntity().getKiller();
            handleKill(killer);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();

        // Cancel cooldown task if exists
        if (cooldownTasks.containsKey(playerName)) {
            cooldownTasks.get(playerName).cancel();
            cooldownTasks.remove(playerName);
        }

        // Remove from isLevel5Cooldown map
        isLevel5CooldownRemove(playerName);

        // Reset star level and cancel mobs
        resetStarLevelAndCancelMobs(playerName);
    }

    private void handleKill(Player killer) {
        if (economy == null) {
            getLogger().warning("Economy not initialized for player " + killer.getName());
            return;
        }

        String killerName = killer.getName();
        int currentLevel = starLevels.getOrDefault(killerName, 0);
        int killCount = killCounts.getOrDefault(killerName, 0) + 1;

        killCounts.put(killerName, killCount);

        if (currentLevel == 5) {
            // ถ้าอยู่ในช่วงคูลดาวน์ของระดับ 5 และทำการฆ่า ให้รีเซ็ตคูลดาวน์ใหม่
            if (isLevel5Cooldown.getOrDefault(killerName, false)) {
                if (cooldownTasks.containsKey(killerName)) {
                    cooldownTasks.get(killerName).cancel();
                }

                long resetTime = cooldownTimes.getOrDefault(5, 20L) * 20L;

                BukkitRunnable newCooldownTask = new BukkitRunnable() {
                    @Override
                    public void run() {
                        resetStarLevelAndCancelMobs(killerName);
                        Player player = getServer().getPlayer(killerName);
                        if (player != null && player.isOnline()) {
                            player.sendMessage("§cดาวของคุณถูกรีเซ็ตหลังจากคูลดาวน์หมด.");
                        }
                    }
                };

                cooldownTasks.put(killerName, newCooldownTask);
                newCooldownTask.runTaskLater(this, resetTime);

                // ตั้งค่าว่าผู้เล่นอยู่ในช่วงคูลดาวน์ระดับ 5
                isLevel5CooldownPut(killerName, true);
            }
        } else if (shouldGainStar(killCount, currentLevel)) {
            int newLevel = Math.min(currentLevel + 1, 5);
            starLevels.put(killerName, newLevel);
            killCounts.put(killerName, 0); // Reset kill count after gaining a star

            killer.sendMessage("§aคุณได้รับดาวระดับ " + newLevel + " จากการฆ่า!");

            // Spawn MysticMobs based on the new star level
            spawnMysticMobsForPlayer(killer, newLevel);

            final long finalResetTime = cooldownTimes.getOrDefault(newLevel, 20L) * 20L; // Convert to ticks

            // Cancel previous cooldown task if exists
            if (cooldownTasks.containsKey(killerName)) {
                cooldownTasks.get(killerName).cancel();
            }

            // Start new cooldown task
            BukkitRunnable newCooldownTask = new BukkitRunnable() {
                @Override
                public void run() {
                    resetStarLevelAndCancelMobs(killerName);
                    Player player = getServer().getPlayer(killerName);
                    if (player != null && player.isOnline()) {
                        player.sendMessage("§cดาวของคุณถูกรีเซ็ตหลังจากคูลดาวน์หมด.");
                    }
                }
            };
            cooldownTasks.put(killerName, newCooldownTask);
            newCooldownTask.runTaskLater(this, finalResetTime);

            // ถ้าเป็นดาวระดับ 5 ตั้งค่าว่าผู้เล่นอยู่ในคูลดาวน์
            if (newLevel == 5) {
                isLevel5CooldownPut(killerName, true);
            }
        }
    }

    private boolean shouldGainStar(int killCount, int currentLevel) {
        int requiredKills = getConfig().getInt("kill_thresholds.star_" + (currentLevel + 1), 0);
        if (killCount < requiredKills) {
            return false;
        }

        int chance = getConfig().getInt("chance_to_gain_star.star_" + (currentLevel + 1), 0);
        return ThreadLocalRandom.current().nextDouble(0, 100) < chance;
    }

    private class CommandHandler implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (command.getName().equalsIgnoreCase("wanted")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§cคำสั่งนี้สามารถใช้ได้เฉพาะผู้เล่นเท่านั้น");
                    return true;
                }

                Player player = (Player) sender;

                if (!player.hasPermission("Wanted.*")) {
                    player.sendMessage(getConfig().getString("no_permission", "§cคุณไม่มีสิทธิ์ใช้คำสั่งนี้"));
                    return true;
                }

                if (args.length < 1) {
                    player.sendMessage(getConfig().getString("usage", "§cใช้งาน: /wanted [add|set|clear|cooldown|reload] <player> <amount>"));
                    return true;
                }

                String subCommand = args[0];

                switch (subCommand.toLowerCase()) {
                    case "set":
                        if (args.length < 3) {
                            player.sendMessage(getConfig().getString("set_usage", "§cใช้งาน: /wanted set <player> <amount>"));
                            return true;
                        }
                        try {
                            int amount = Integer.parseInt(args[2]);
                            if (amount < 0 || amount > 5) {
                                player.sendMessage(getConfig().getString("invalid_amount", "§cจำนวนที่ไม่ถูกต้อง! ระบุจำนวนระหว่าง 0 ถึง 5"));
                                return true;
                            }
                            String targetSetName = args[1];
                            starLevels.put(targetSetName, amount);
                            killCounts.put(targetSetName, 0); // Reset kill counts when setting star level
                            if (amount == 5) {
                                // หากตั้งค่าเป็นดาวระดับ 5 เริ่มคูลดาวน์ใหม่
                                startLevel5Cooldown(targetSetName);
                            } else {
                                // ยกเลิกคูลดาวน์ถ้ามี
                                if (cooldownTasks.containsKey(targetSetName)) {
                                    cooldownTasks.get(targetSetName).cancel();
                                    cooldownTasks.remove(targetSetName);
                                }
                                isLevel5CooldownRemove(targetSetName);
                                // ยกเลิกการติดตามของมอนสเตอร์
                                cancelMobsTargetingPlayer(targetSetName);
                            }

                            player.sendMessage(String.format(getConfig().getString("star_set", "§aตั้งค่าระดับดาวให้กับ §e%s §aเป็น §e%d §aดาวเรียบร้อยแล้ว!"), targetSetName, amount));

                            return true;
                        } catch (NumberFormatException e) {
                            player.sendMessage(getConfig().getString("not_a_number", "§cโปรดระบุจำนวนที่เป็นตัวเลข"));
                            return true;
                        }

                    case "add":
                        if (args.length < 3) {
                            player.sendMessage(getConfig().getString("add_usage", "§cใช้งาน: /wanted add <player> <amount>"));
                            return true;
                        }
                        try {
                            int addAmount = Integer.parseInt(args[2]);
                            if (addAmount < 0 || addAmount > 5) {
                                player.sendMessage(getConfig().getString("invalid_amount", "§cจำนวนที่ไม่ถูกต้อง! ระบุจำนวนระหว่าง 0 ถึง 5"));
                                return true;
                            }
                            String targetAddName = args[1];
                            int currentStars = starLevels.getOrDefault(targetAddName, 0);
                            int newStars = Math.min(currentStars + addAmount, 5);  // Maximum 5 stars
                            starLevels.put(targetAddName, newStars);
                            killCounts.put(targetAddName, 0); // Reset kill counts when adding stars
                            player.sendMessage(String.format(getConfig().getString("star_added", "§aเพิ่มระดับดาวให้กับ §e%s §aจำนวน §e%d §aดาวเรียบร้อยแล้ว!"), targetAddName, newStars));

                            // หากเป็นดาวระดับ 5 ให้เริ่มคูลดาวน์ใหม่
                            if (newStars == 5) {
                                startLevel5Cooldown(targetAddName);
                            } else {
                                // ยกเลิกคูลดาวน์ถ้ามี
                                if (cooldownTasks.containsKey(targetAddName)) {
                                    cooldownTasks.get(targetAddName).cancel();
                                    cooldownTasks.remove(targetAddName);
                                }
                                isLevel5CooldownRemove(targetAddName);
                                // ยกเลิกการติดตามของมอนสเตอร์
                                cancelMobsTargetingPlayer(targetAddName);
                            }

                            return true;
                        } catch (NumberFormatException e) {
                            player.sendMessage(getConfig().getString("not_a_number", "§cโปรดระบุจำนวนที่เป็นตัวเลข"));
                            return true;
                        }

                    case "clear":
                        if (args.length < 2) {
                            player.sendMessage(getConfig().getString("invalid_player", "§cไม่พบผู้เล่นที่ระบุ"));
                            return true;
                        }
                        String targetClearName = args[1];
                        Player targetClearPlayer = getServer().getPlayer(targetClearName);
                        if (targetClearPlayer == null || !targetClearPlayer.isOnline()) {
                            player.sendMessage(getConfig().getString("player_offline", "§cผู้เล่นนี้ไม่ได้อยู่ในเกม"));
                            return true;
                        }

                        // Clear star level and cancel mobs
                        resetStarLevelAndCancelMobs(targetClearName);
                        killCounts.put(targetClearName, 0); // Reset kill counts
                        player.sendMessage(String.format(getConfig().getString("star_cleared", "§aล้างระดับดาวของ §e%s §aเรียบร้อยแล้ว!"), targetClearName));

                        return true;

                    case "cooldown":
                        if (args.length < 2) {
                            player.sendMessage(getConfig().getString("invalid_player", "§cไม่พบผู้เล่นที่ระบุ"));
                            return true;
                        }
                        String targetCooldownName = args[1];
                        if (starLevels.containsKey(targetCooldownName) && starLevels.get(targetCooldownName) > 0) {
                            // Inform the player about the current star level and cooldown
                            int currentStar = starLevels.get(targetCooldownName);
                            long cooldownSeconds = cooldownTimes.getOrDefault(currentStar, 20L);
                            player.sendMessage(String.format(getConfig().getString("cooldown_active", "§cผู้เล่น §e%s §cอยู่ในช่วงคูลดาวน์: §e%d วินาที"), targetCooldownName, cooldownSeconds));
                        } else {
                            player.sendMessage(String.format(getConfig().getString("no_cooldown", "§aผู้เล่น §e%s §aไม่มีคูลดาวน์ในขณะนี้"), targetCooldownName));
                        }
                        return true;

                    case "reload":
                        reloadConfig();  // Reload config.yml
                        loadLossPercentages();
                        loadCooldownTimes();
                        player.sendMessage("§aรีโหลดการตั้งค่าเรียบร้อยแล้ว!");
                        return true;

                    default:
                        player.sendMessage(getConfig().getString("usage", "§cใช้งาน: /wanted [add|set|clear|cooldown|reload] <player> <amount>"));
                        return true;
                }
            }
            return true;
        }
    }

    private void startLevel5Cooldown(String playerName) {
        // Cancel previous cooldown if it exists
        if (cooldownTasks.containsKey(playerName)) {
            cooldownTasks.get(playerName).cancel();
        }

        // Start a new cooldown task for level 5
        long resetTime = cooldownTimes.getOrDefault(5, 20L) * 20L; // Convert to ticks

        BukkitRunnable newCooldownTask = new BukkitRunnable() {
            @Override
            public void run() {
                resetStarLevelAndCancelMobs(playerName);
                Player player = getServer().getPlayer(playerName);
                if (player != null && player.isOnline()) {
                    player.sendMessage("§cดาวของคุณถูกรีเซ็ตหลังจากคูลดาวน์หมด.");
                }
            }
        };

        cooldownTasks.put(playerName, newCooldownTask);
        newCooldownTask.runTaskLater(this, resetTime);

        // Set the player in level 5 cooldown
        isLevel5CooldownPut(playerName, true);
    }

    // Helper methods to manage isLevel5Cooldown and reset star level
    private void isLevel5CooldownPut(String playerName, boolean status) {
        isLevel5Cooldown.put(playerName, status);
    }

    private void isLevel5CooldownRemove(String playerName) {
        isLevel5Cooldown.remove(playerName);
    }

    private void resetStarLevelAndCancelMobs(String playerName) {
        // Reset star level to 0
        starLevels.put(playerName, 0);
        killCounts.put(playerName, 0); // Reset kill counts

        // Cancel mobs targeting the player
        Player player = getServer().getPlayer(playerName);
        if (player != null && player.isOnline()) {
            for (Entity entity : player.getWorld().getEntities()) {
                if (entity instanceof Creature) {
                    Creature creature = (Creature) entity;
                    if (creature.getTarget() != null && creature.getTarget().equals(player)) {
                        creature.setTarget(null);  // Remove the player as a target
                    }
                }
            }
        }

        // Remove cooldown task if exists
        if (cooldownTasks.containsKey(playerName)) {
            cooldownTasks.get(playerName).cancel();
            cooldownTasks.remove(playerName);
        }

        // Remove from isLevel5Cooldown map
        isLevel5CooldownRemove(playerName);
    }

    // Method to cancel mobs targeting a specific player
    private void cancelMobsTargetingPlayer(String playerName) {
        Player player = getServer().getPlayer(playerName);
        if (player != null && player.isOnline()) {
            for (Entity entity : player.getWorld().getEntities()) {
                if (entity instanceof Creature) {
                    Creature creature = (Creature) entity;
                    if (creature.getTarget() != null && creature.getTarget().equals(player)) {
                        creature.setTarget(null);  // Remove the player as a target
                    }
                }
            }
        }
    }
}