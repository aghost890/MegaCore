package me.server.megacore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.BlockFace;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class MegaCore extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String[] COMMANDS = {
            "spawn", "setspawn", "home", "sethome", "delhome", "homes",
            "warp", "setwarp", "delwarp", "warps", "tpa", "tpaccept", "tpdeny",
            "back", "rtp", "heal", "feed", "fly", "gm", "kit", "kits",
            "balance", "pay", "eco", "woodbook"
    };

    private File dataFile;
    private YamlConfiguration data;
    private String prefix;
    private final Random rnd = new Random();
    private NamespacedKey instaKey;
    private NamespacedKey areaKey;
    private NamespacedKey bookKey;
    private final Set<UUID> breaking = new HashSet<>();

    private final Map<UUID, UUID> tpaRequests = new HashMap<>();   // target -> requester
    private final Map<UUID, Long> tpaTime = new HashMap<>();       // target -> time
    private final Map<UUID, Location> backLocations = new HashMap<>();

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        prefix = getConfig().getString("prefix", "&8[&bMega&8] &7");
        instaKey = new NamespacedKey(this, "woodinsta");
        areaKey = new NamespacedKey(this, "woodarea");
        bookKey = new NamespacedKey(this, "woodbook");

        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        getServer().getPluginManager().registerEvents(this, this);
        for (String name : COMMANDS) {
            if (getCommand(name) != null) {
                getCommand(name).setExecutor(this);
                getCommand(name).setTabCompleter(this);
            }
        }
        getLogger().info("MegaCore enabled.");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save data.yml: " + e.getMessage());
        }
    }

    private Component color(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    private void msg(CommandSender to, String text) {
        to.sendMessage(color(prefix + text));
    }

    private void tp(Player p, Location dest) {
        backLocations.put(p.getUniqueId(), p.getLocation());
        p.teleport(dest);
    }

    private boolean isAdmin(Player p) {
        return p.hasPermission("megacore.admin");
    }

    private String money(double v) {
        return getConfig().getString("economy.symbol", "$") + String.format("%.2f", v);
    }

    private double getBalance(UUID id) {
        return data.getDouble("balances." + id, getConfig().getDouble("economy.starting-balance", 1000));
    }

    private void setBalance(UUID id, double v) {
        data.set("balances." + id, Math.max(0, v));
        saveData();
    }

    private boolean validName(String s) {
        return s.matches("[a-z0-9_-]{1,24}");
    }

    private List<String> keys(String path) {
        ConfigurationSection sec = data.getConfigurationSection(path);
        return sec == null ? new ArrayList<>() : new ArrayList<>(sec.getKeys(false));
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String jm = getConfig().getString("join-message", "");
        e.joinMessage(jm.isEmpty() ? null : color(jm.replace("%player%", p.getName())));

        String welcome = getConfig().getString("welcome", "");
        if (!welcome.isEmpty()) {
            p.sendMessage(color(welcome.replace("%player%", p.getName())));
        }
        if (!data.contains("balances." + p.getUniqueId())) {
            setBalance(p.getUniqueId(), getConfig().getDouble("economy.starting-balance", 1000));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        String qm = getConfig().getString("quit-message", "");
        e.quitMessage(qm.isEmpty() ? null : color(qm.replace("%player%", e.getPlayer().getName())));
        tpaRequests.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        backLocations.put(e.getEntity().getUniqueId(), e.getEntity().getLocation());
    }

    // ------------------------------------------------------------------
    // Lumber upgrades (two separate books)
    //   insta = break wood in one hit
    //   area  = break wood 3x3
    // ------------------------------------------------------------------

    private static boolean validType(String t) {
        return t != null && (t.equals("insta") || t.equals("area"));
    }

    private NamespacedKey upgradeKey(String type) {
        return type.equals("insta") ? instaKey : areaKey;
    }

    private ItemStack createWoodBook(String type) {
        String base = "upgrades." + type + ".";
        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(color(getConfig().getString(base + "book-name", "&6كتاب الحطّاب"))
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : getConfig().getStringList(base + "book-lore")) {
            lore.add(color(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(bookKey, PersistentDataType.STRING, type);
        it.setItemMeta(meta);
        return it;
    }

    private String bookType(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        String t = it.getItemMeta().getPersistentDataContainer().get(bookKey, PersistentDataType.STRING);
        return validType(t) ? t : null;
    }

    private boolean hasUpgrade(ItemStack it, String type) {
        if (it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(upgradeKey(type), PersistentDataType.BYTE);
    }

    private boolean isWood(Block b) {
        Material t = b.getType();
        if (Tag.LOGS.isTagged(t)) return true;
        return getConfig().getBoolean("upgrades.break-planks", true) && Tag.PLANKS.isTagged(t);
    }

    private void damageTool(Player p, ItemStack tool, int amount) {
        if (amount <= 0 || p.getGameMode() == GameMode.CREATIVE) return;
        ItemMeta m = tool.getItemMeta();
        if (m instanceof Damageable d && !d.isUnbreakable()) {
            d.setDamage(d.getDamage() + amount);
            if (d.getDamage() >= tool.getType().getMaxDurability()) {
                p.getInventory().setItemInMainHand(null);
            } else {
                tool.setItemMeta(m);
                p.getInventory().setItemInMainHand(tool);
            }
        }
    }

    // Drag a book onto an axe inside your inventory to apply it
    @EventHandler
    public void onApplyBook(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack cursor = e.getCursor();
        ItemStack target = e.getCurrentItem();
        String type = bookType(cursor);
        if (type == null || target == null || target.getType().isAir()) return;
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(p.getInventory())) return;

        e.setCancelled(true);

        if (!target.getType().name().endsWith("_AXE")) {
            msg(p, "&cالكتاب هذا يتركب على الفووس فقط.");
            return;
        }
        if (hasUpgrade(target, type)) {
            msg(p, "&cهذا التطوير مركّب على الفأس من قبل.");
            return;
        }

        ItemStack upgraded = target.clone();
        ItemMeta meta = upgraded.getItemMeta();
        meta.getPersistentDataContainer().set(upgradeKey(type), PersistentDataType.BYTE, (byte) 1);
        List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(color(getConfig().getString("upgrades." + type + ".axe-lore-line", "&6✦ تطوير الحطّاب"))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        upgraded.setItemMeta(meta);

        int slot = e.getSlot();
        ItemStack leftover = cursor.clone();
        leftover.setAmount(cursor.getAmount() - 1);

        Bukkit.getScheduler().runTask(this, () -> {
            p.getInventory().setItem(slot, upgraded);
            p.setItemOnCursor(leftover.getAmount() > 0 ? leftover : null);
            p.updateInventory();
            msg(p, "&aتم تركيب التطوير على الفأس!");
        });
    }

    // INSTA: one-hit wood
    @EventHandler(ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent e) {
        if (hasUpgrade(e.getItemInHand(), "insta") && isWood(e.getBlock())) {
            e.setInstaBreak(true);
        }
    }

    // AREA: 3x3 wood
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWoodBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (breaking.contains(id)) return;

        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!hasUpgrade(tool, "area") || !isWood(e.getBlock())) return;
        if (p.isSneaking()) return;

        BlockFace face = p.getTargetBlockFace(6);
        if (face == null) face = BlockFace.UP;
        Block center = e.getBlock();

        int extra = 0;
        breaking.add(id);
        try {
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    if (i == 0 && j == 0) continue;
                    int dx = 0, dy = 0, dz = 0;
                    switch (face) {
                        case UP, DOWN -> {
                            dx = i;
                            dz = j;
                        }
                        case NORTH, SOUTH -> {
                            dx = i;
                            dy = j;
                        }
                        default -> {
                            dz = i;
                            dy = j;
                        }
                    }
                    Block b = center.getRelative(dx, dy, dz);
                    if (!isWood(b)) continue;

                    BlockBreakEvent ev = new BlockBreakEvent(b, p);
                    Bukkit.getPluginManager().callEvent(ev);
                    if (ev.isCancelled()) continue;

                    if (p.getGameMode() == GameMode.CREATIVE) {
                        b.setType(Material.AIR);
                    } else {
                        b.breakNaturally(tool);
                    }
                    extra++;
                }
            }
        } finally {
            breaking.remove(id);
        }
        damageTool(p, tool, extra * getConfig().getInt("upgrades.area.damage-per-extra-block", 1));
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        String n = cmd.getName().toLowerCase();

        if (n.equals("eco")) {
            return ecoCommand(sender, a);
        }
        if (!(sender instanceof Player p)) {
            msg(sender, "&cهذا الأمر للاعبين فقط.");
            return true;
        }
        UUID id = p.getUniqueId();

        switch (n) {
            case "spawn" -> {
                Location l = data.getLocation("spawn");
                if (l == null) l = p.getWorld().getSpawnLocation();
                tp(p, l);
                msg(p, "تم نقلك إلى السبون.");
            }
            case "setspawn" -> {
                data.set("spawn", p.getLocation());
                saveData();
                msg(p, "تم تحديد السبون.");
            }
            case "sethome" -> {
                String name = a.length > 0 ? a[0].toLowerCase() : "home";
                if (!validName(name)) {
                    msg(p, "&cاسم غير صالح (حروف انجليزي وأرقام فقط).");
                    return true;
                }
                List<String> homes = keys("homes." + id);
                int max = getConfig().getInt("max-homes", 3);
                if (!homes.contains(name) && homes.size() >= max && !isAdmin(p)) {
                    msg(p, "&cوصلت الحد الأقصى للهومات (" + max + ").");
                    return true;
                }
                data.set("homes." + id + "." + name, p.getLocation());
                saveData();
                msg(p, "تم حفظ الهوم &f" + name + "&7.");
            }
            case "home" -> {
                String name = a.length > 0 ? a[0].toLowerCase() : "home";
                Location l = data.getLocation("homes." + id + "." + name);
                if (l == null) {
                    msg(p, "&cما عندك هوم بهذا الاسم. شوف /homes");
                    return true;
                }
                tp(p, l);
                msg(p, "تم نقلك إلى &f" + name + "&7.");
            }
            case "delhome" -> {
                if (a.length < 1) {
                    msg(p, "&cالاستخدام: /delhome <اسم>");
                    return true;
                }
                String name = a[0].toLowerCase();
                if (data.get("homes." + id + "." + name) == null) {
                    msg(p, "&cما فيه هوم بهذا الاسم.");
                    return true;
                }
                data.set("homes." + id + "." + name, null);
                saveData();
                msg(p, "تم حذف الهوم &f" + name + "&7.");
            }
            case "homes" -> {
                List<String> homes = keys("homes." + id);
                msg(p, homes.isEmpty() ? "ما عندك هومات." : "هوماتك: &f" + String.join("&7, &f", homes));
            }
            case "setwarp" -> {
                if (a.length < 1 || !validName(a[0].toLowerCase())) {
                    msg(p, "&cالاستخدام: /setwarp <اسم> (حروف انجليزي وأرقام)");
                    return true;
                }
                data.set("warps." + a[0].toLowerCase(), p.getLocation());
                saveData();
                msg(p, "تم إنشاء الوارب &f" + a[0].toLowerCase() + "&7.");
            }
            case "delwarp" -> {
                if (a.length < 1 || data.get("warps." + a[0].toLowerCase()) == null) {
                    msg(p, "&cالوارب غير موجود.");
                    return true;
                }
                data.set("warps." + a[0].toLowerCase(), null);
                saveData();
                msg(p, "تم حذف الوارب.");
            }
            case "warp" -> {
                if (a.length < 1) {
                    msg(p, "&cالاستخدام: /warp <اسم>  |  /warps");
                    return true;
                }
                Location l = data.getLocation("warps." + a[0].toLowerCase());
                if (l == null) {
                    msg(p, "&cالوارب غير موجود.");
                    return true;
                }
                tp(p, l);
                msg(p, "تم نقلك إلى الوارب &f" + a[0].toLowerCase() + "&7.");
            }
            case "warps" -> {
                List<String> w = keys("warps");
                msg(p, w.isEmpty() ? "ما فيه وارب." : "الوارب: &f" + String.join("&7, &f", w));
            }
            case "tpa" -> {
                if (a.length < 1) {
                    msg(p, "&cالاستخدام: /tpa <لاعب>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t == null || t.equals(p)) {
                    msg(p, "&cاللاعب غير متصل.");
                    return true;
                }
                tpaRequests.put(t.getUniqueId(), id);
                tpaTime.put(t.getUniqueId(), System.currentTimeMillis());
                msg(p, "تم إرسال الطلب إلى &f" + t.getName() + "&7.");
                msg(t, "&f" + p.getName() + " &7يبي ينتقل لك. اكتب &a/tpaccept &7أو &c/tpdeny");
            }
            case "tpaccept" -> {
                UUID from = tpaRequests.remove(id);
                Long time = tpaTime.remove(id);
                long timeout = getConfig().getLong("tpa-timeout-seconds", 60) * 1000;
                Player req = from == null ? null : Bukkit.getPlayer(from);
                if (req == null || time == null || System.currentTimeMillis() - time > timeout) {
                    msg(p, "&cما عندك طلبات (أو انتهت).");
                    return true;
                }
                tp(req, p.getLocation());
                msg(req, "تم قبول طلبك.");
                msg(p, "تم قبول الطلب.");
            }
            case "tpdeny" -> {
                UUID from = tpaRequests.remove(id);
                tpaTime.remove(id);
                if (from == null) {
                    msg(p, "&cما عندك طلبات.");
                    return true;
                }
                Player req = Bukkit.getPlayer(from);
                if (req != null) msg(req, "&c" + p.getName() + " رفض طلبك.");
                msg(p, "تم رفض الطلب.");
            }
            case "back" -> {
                Location l = backLocations.get(id);
                if (l == null) {
                    msg(p, "&cما فيه مكان سابق.");
                    return true;
                }
                tp(p, l);
                msg(p, "رجعت لمكانك السابق.");
            }
            case "rtp" -> {
                World w = p.getWorld();
                if (w.getEnvironment() != World.Environment.NORMAL) {
                    msg(p, "&cالـ RTP يشتغل في العالم العادي فقط.");
                    return true;
                }
                int r = getConfig().getInt("rtp-radius", 5000);
                Location base = w.getSpawnLocation();
                for (int i = 0; i < 20; i++) {
                    int x = base.getBlockX() + rnd.nextInt(-r, r + 1);
                    int z = base.getBlockZ() + rnd.nextInt(-r, r + 1);
                    int y = w.getHighestBlockYAt(x, z);
                    Block b = w.getBlockAt(x, y, z);
                    if (b.isLiquid() || b.getType() == Material.LAVA) continue;
                    tp(p, new Location(w, x + 0.5, y + 1, z + 0.5));
                    msg(p, "تم نقلك لمكان عشوائي: &f" + x + ", " + (y + 1) + ", " + z);
                    return true;
                }
                msg(p, "&cما لقيت مكان آمن، جرب مرة ثانية.");
            }
            case "heal" -> {
                Player t = a.length > 0 ? Bukkit.getPlayerExact(a[0]) : p;
                if (t == null) {
                    msg(p, "&cاللاعب غير متصل.");
                    return true;
                }
                var attr = t.getAttribute(Attribute.MAX_HEALTH);
                t.setHealth(attr != null ? attr.getValue() : 20);
                t.setFoodLevel(20);
                msg(p, "تم علاج &f" + t.getName() + "&7.");
            }
            case "feed" -> {
                Player t = a.length > 0 ? Bukkit.getPlayerExact(a[0]) : p;
                if (t == null) {
                    msg(p, "&cاللاعب غير متصل.");
                    return true;
                }
                t.setFoodLevel(20);
                t.setSaturation(20f);
                msg(p, "تم إشباع &f" + t.getName() + "&7.");
            }
            case "fly" -> {
                boolean on = !p.getAllowFlight();
                p.setAllowFlight(on);
                if (!on) p.setFlying(false);
                msg(p, "الطيران: " + (on ? "&aمفعّل" : "&cمعطّل"));
            }
            case "gm" -> {
                if (a.length < 1) {
                    msg(p, "&cالاستخدام: /gm <0|1|2|3> [لاعب]");
                    return true;
                }
                GameMode gm = switch (a[0].toLowerCase()) {
                    case "0", "s", "survival" -> GameMode.SURVIVAL;
                    case "1", "c", "creative" -> GameMode.CREATIVE;
                    case "2", "a", "adventure" -> GameMode.ADVENTURE;
                    case "3", "sp", "spectator" -> GameMode.SPECTATOR;
                    default -> null;
                };
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : p;
                if (gm == null || t == null) {
                    msg(p, "&cوضع أو لاعب غير صحيح.");
                    return true;
                }
                t.setGameMode(gm);
                msg(p, "تم تغيير وضع &f" + t.getName() + " &7إلى &f" + gm.name().toLowerCase());
            }
            case "woodbook" -> {
                if (a.length < 1 || !validType(a[0].toLowerCase())) {
                    msg(p, "&cالاستخدام: /woodbook <insta|area> [لاعب]");
                    return true;
                }
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : p;
                if (t == null) {
                    msg(p, "&cاللاعب غير متصل.");
                    return true;
                }
                t.getInventory().addItem(createWoodBook(a[0].toLowerCase()));
                msg(p, "تم إعطاء &f" + t.getName() + " &7الكتاب.");
            }
            case "kits" -> {
                ConfigurationSection sec = getConfig().getConfigurationSection("kits");
                List<String> list = sec == null ? new ArrayList<>() : new ArrayList<>(sec.getKeys(false));
                msg(p, list.isEmpty() ? "ما فيه كيتات." : "الكيتات: &f" + String.join("&7, &f", list));
            }
            case "kit" -> {
                if (a.length < 1) {
                    msg(p, "&cالاستخدام: /kit <اسم>  |  /kits");
                    return true;
                }
                String kit = a[0].toLowerCase();
                ConfigurationSection ks = getConfig().getConfigurationSection("kits." + kit);
                if (ks == null) {
                    msg(p, "&cالكيت غير موجود.");
                    return true;
                }
                String cdPath = "kitcd." + id + "." + kit;
                long next = data.getLong(cdPath, 0);
                long now = System.currentTimeMillis();
                if (now < next && !isAdmin(p)) {
                    long left = (next - now) / 1000;
                    msg(p, "&cانتظر " + (left / 3600) + "س " + ((left % 3600) / 60) + "د " + (left % 60) + "ث");
                    return true;
                }
                for (String entry : ks.getStringList("items")) {
                    String[] parts = entry.split(":");
                    Material m = Material.matchMaterial(parts[0]);
                    if (m == null) continue;
                    int amount = 1;
                    if (parts.length > 1) {
                        try {
                            amount = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(m, amount));
                    left.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
                }
                data.set(cdPath, now + ks.getLong("cooldown", 0) * 1000);
                saveData();
                msg(p, "استلمت الكيت &f" + kit + "&7.");
            }
            case "balance" -> {
                Player t = a.length > 0 ? Bukkit.getPlayerExact(a[0]) : p;
                if (t == null) {
                    msg(p, "&cاللاعب غير متصل.");
                    return true;
                }
                msg(p, "رصيد &f" + t.getName() + "&7: &a" + money(getBalance(t.getUniqueId())));
            }
            case "pay" -> {
                if (a.length < 2) {
                    msg(p, "&cالاستخدام: /pay <لاعب> <مبلغ>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t == null || t.equals(p)) {
                    msg(p, "&cاللاعب غير متصل.");
                    return true;
                }
                double amt;
                try {
                    amt = Double.parseDouble(a[1]);
                } catch (NumberFormatException ex) {
                    msg(p, "&cمبلغ غير صحيح.");
                    return true;
                }
                if (amt <= 0 || Double.isNaN(amt) || Double.isInfinite(amt)) {
                    msg(p, "&cمبلغ غير صحيح.");
                    return true;
                }
                if (getBalance(id) < amt) {
                    msg(p, "&cرصيدك ما يكفي.");
                    return true;
                }
                setBalance(id, getBalance(id) - amt);
                setBalance(t.getUniqueId(), getBalance(t.getUniqueId()) + amt);
                msg(p, "حولت &a" + money(amt) + " &7إلى &f" + t.getName());
                msg(t, "استلمت &a" + money(amt) + " &7من &f" + p.getName());
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean ecoCommand(CommandSender s, String[] a) {
        if (!s.hasPermission("megacore.admin")) {
            msg(s, "&cما عندك صلاحية.");
            return true;
        }
        if (a.length < 3) {
            msg(s, "&cالاستخدام: /eco <give|take|set> <لاعب> <مبلغ>");
            return true;
        }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) {
            msg(s, "&cاللاعب غير متصل.");
            return true;
        }
        double amt;
        try {
            amt = Double.parseDouble(a[2]);
        } catch (NumberFormatException ex) {
            msg(s, "&cمبلغ غير صحيح.");
            return true;
        }
        if (Double.isNaN(amt) || Double.isInfinite(amt) || amt < 0) {
            msg(s, "&cمبلغ غير صحيح.");
            return true;
        }
        UUID id = t.getUniqueId();
        switch (a[0].toLowerCase()) {
            case "give" -> setBalance(id, getBalance(id) + amt);
            case "take" -> setBalance(id, getBalance(id) - amt);
            case "set" -> setBalance(id, amt);
            default -> {
                msg(s, "&cاستخدم give أو take أو set.");
                return true;
            }
        }
        msg(s, "رصيد &f" + t.getName() + " &7الآن: &a" + money(getBalance(id)));
        return true;
    }

    // ------------------------------------------------------------------
    // Tab completion
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        String n = cmd.getName().toLowerCase();

        if (a.length == 1) {
            switch (n) {
                case "home", "delhome" -> {
                    if (sender instanceof Player p) out.addAll(keys("homes." + p.getUniqueId()));
                }
                case "warp", "delwarp" -> out.addAll(keys("warps"));
                case "kit" -> {
                    ConfigurationSection sec = getConfig().getConfigurationSection("kits");
                    if (sec != null) out.addAll(sec.getKeys(false));
                }
                case "gm" -> out.addAll(List.of("0", "1", "2", "3"));
                case "eco" -> out.addAll(List.of("give", "take", "set"));
                case "woodbook" -> out.addAll(List.of("insta", "area"));
                case "tpa", "pay", "balance", "heal", "feed" ->
                        Bukkit.getOnlinePlayers().forEach(pl -> out.add(pl.getName()));
                default -> {
                }
            }
        } else if (a.length == 2 && (n.equals("eco") || n.equals("gm") || n.equals("woodbook"))) {
            Bukkit.getOnlinePlayers().forEach(pl -> out.add(pl.getName()));
        }

        String prefixLower = a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(prefixLower));
        return out;
    }
              }
