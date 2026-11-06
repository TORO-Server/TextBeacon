package prj.salmon.textbeacon;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.data.BlockData;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;
import java.util.stream.Collectors;

public class TextBeacon extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final int DEBUG_PARTICLE_CAP = 1000;
    private static final double DEBUG_LINE_STEP = 0.35D;
    private static final Particle.DustOptions DEBUG_RED_DUST = new Particle.DustOptions(Color.RED, 1.0F);
    private static final String ITEM_MESSAGE_KEY = "tb-message";
    private static final int SEARCH_RADIUS = 5;
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("(?i)&#([0-9a-f]{6})");
    private static final int MAX_CUBE_SIZE = 5;
    private static final int UPPER_BODY_BLOCK_OFFSET = 1;
    private static final BlockFace[] ADJACENT_FACES = new BlockFace[]{
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST,
            BlockFace.UP,
            BlockFace.DOWN
    };

    // world名 + 圧縮座標 → Beacon ID
    private final Map<String, Map<Long, UUID>> triggerMap = new HashMap<>();
    // 看板座標 → Beacon ID
    private final Map<BlockKey, UUID> signMap = new HashMap<>();

    private final Map<UUID, Beacon> beacons = new HashMap<>();
    private final Map<UUID, Set<UUID>> inside = new HashMap<>();
    private final Set<UUID> debugViewers = new HashSet<>();
    private final Set<UUID> mutedPlayers = new HashSet<>();

    private File file;
    private YamlConfiguration yaml;
    private NamespacedKey itemMessageDataKey;

    private boolean saveScheduled = false;
    private int debugTaskId = -1;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        file = new File(getDataFolder(), "data.yml");
        itemMessageDataKey = new NamespacedKey(this, ITEM_MESSAGE_KEY);

        load();
        int removed = pruneMissingSignBeacons();
        if (removed > 0) {
            getLogger().info("Removed " + removed + " beacon(s) with missing signs during startup check.");
            saveNow();
        }
        Bukkit.getPluginManager().registerEvents(this, this);

        PluginCommand tb = getCommand("tb");
        if (tb != null) {
            tb.setExecutor(this);
            tb.setTabCompleter(this);
        } else {
            getLogger().warning("Command 'tb' is not defined in plugin.yml");
        }

        startDebugTask();

        // 同じ権限を他のプラグインが別の既定で登録していても、すべてのプラグインが有効になった後で op に揃える
        Bukkit.getScheduler().runTask(this, () -> {
            Permission permission = Bukkit.getPluginManager().getPermission("textbeacon.create");
            if (permission != null) permission.setDefault(PermissionDefault.OP);
        });
    }

    @Override
    public void onDisable() {
        if (debugTaskId != -1) {
            Bukkit.getScheduler().cancelTask(debugTaskId);
        }
        debugViewers.clear();
        saveNow();
    }

    // =========================
    // 看板設置
    // =========================
    // 保護プラグインの判定が終わった後に処理する
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSign(SignChangeEvent e) {
        if (!e.getLine(0).equalsIgnoreCase("[TextBeacon]")) return;
        if (!e.getPlayer().hasPermission("textbeacon.create")) {
            e.getPlayer().sendMessage("§cこの操作を行う権限がありません (textbeacon.create)");
            return;
        }

        tryRegisterBeacon(e.getPlayer(), e.getBlock().getLocation(), buildMessage(e));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!(e.getBlockPlaced().getState() instanceof Sign sign)) return;

        String itemMessage = extractItemMessage(e.getItemInHand());
        boolean hasItemMessage = itemMessage != null && !itemMessage.isBlank();

        // TextBeaconの看板でなければ何もしない
        if (!hasItemMessage && !"[TextBeacon]".equalsIgnoreCase(sign.getSide(Side.FRONT).getLine(0))) return;

        if (!e.getPlayer().hasPermission("textbeacon.create")) {
            e.getPlayer().sendMessage("§cこの操作を行う権限がありません (textbeacon.create)");
            return;
        }

        if (hasItemMessage) {
            e.setCancelled(true);
            placeBeaconSign(e, sign.getLocation(), itemMessage);
            return;
        }

        BlockKey signKey = new BlockKey(sign.getLocation());
        if (signMap.containsKey(signKey)) return;

        String msg = buildMessage(sign.getSide(Side.FRONT).getLines());
        tryRegisterBeacon(e.getPlayer(), sign.getLocation(), msg);
    }

    private boolean tryRegisterBeacon(Player player, Location base, String msg) {
        msg = colorizeMessage(msg);
        if (msg.isBlank()) {
            player.sendMessage("§c看板の2〜4行目にメッセージを入力してください");
            return false;
        }

        Block nearestGlass = findNearestGlassInRadius(base, SEARCH_RADIUS);
        if (nearestGlass == null) {
            player.sendMessage("§c半径5ブロック以内に空色のガラスが見つかりませんでした");
            return false;
        }

        List<Location> glass = collectConnectedGlass(nearestGlass);

        if (glass == null || !isWithin5Cube(glass)) {
            player.sendMessage("§cガラスの範囲は5x5x5以内にしてください");
            return false;
        }

        if (!canBreakAll(player, glass)) {
            player.sendMessage("§c保護されたガラスが含まれているため、Beaconを設置できません");
            return false;
        }

        UUID id = UUID.randomUUID();
        Beacon beacon = new Beacon(id, base, msg, player.getUniqueId(), player.getName());
        signMap.put(beacon.sign, id);

        for (Location loc : glass) {
            BlockKey key = new BlockKey(loc);
            putTrigger(key, id);
            beacon.blocks.add(key);

            playBlockBreakEffect(loc.getBlock());
            loc.getBlock().setType(Material.AIR);
        }

        beacons.put(id, beacon);
        player.sendMessage("§aBeaconを設置しました: " + glass.size() + "ブロック");

        scheduleSave();
        return true;
    }

    // すべてのブロックについて、プレイヤーが壊してよいかを保護プラグインなどに確認する
    // 1つでも拒否されたらどのブロックも消さないよう、消す前にまとめて確認する
    private boolean canBreakAll(Player player, List<Location> locations) {
        for (Location loc : locations) {
            BlockBreakEvent check = new BlockBreakEvent(loc.getBlock(), player);
            Bukkit.getPluginManager().callEvent(check);
            if (check.isCancelled()) return false;
        }
        return true;
    }

    // =========================
    // 看板破壊
    // =========================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!(e.getBlock().getState() instanceof Sign)) return;

        BlockKey key = new BlockKey(e.getBlock().getLocation());

        UUID id = signMap.get(key);
        if (id == null) return;

        Beacon b = beacons.get(id);
        if (b == null) {
            signMap.remove(key);
            return;
        }

        boolean isOwner = b.owner != null && b.owner.equals(e.getPlayer().getUniqueId());
        if (!e.getPlayer().hasPermission("textbeacon.remove") && !isOwner) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§c他人のBeaconを削除することはできません (textbeacon.remove)");
            return;
        }

        if (!removeBeacon(id, false)) return;

        e.getPlayer().sendMessage("§eTextBeaconを削除しました");
    }

    // =========================
    // 移動検知（軽量版）
    // =========================
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        Location from = e.getFrom();

        if (to == null || from == null || to.getWorld() == null) return;

        if (to.getBlockX() == from.getBlockX() &&
            to.getBlockY() == from.getBlockY() &&
            to.getBlockZ() == from.getBlockZ()) return;

        Player p = e.getPlayer();
        if (!p.hasPermission("textbeacon.use")) {
            inside.remove(p.getUniqueId());
            return;
        }
        if (mutedPlayers.contains(p.getUniqueId())) {
            inside.remove(p.getUniqueId());
            return;
        }

        UUID beaconId = getTriggeredBeaconId(p, to);

        Set<UUID> set = inside.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());

        if (beaconId == null) {
            if (!set.isEmpty()) {
                set.clear();
            }
            return;
        }

        if (set.contains(beaconId)) return;

        // 他Beaconから移動した場合は状態を更新して再入場通知を有効にする
        if (!set.isEmpty()) {
            set.clear();
        }

        Beacon b = beacons.get(beaconId);
        if (b != null) {
            p.sendMessage(b.message);
            set.add(beaconId);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        inside.remove(e.getPlayer().getUniqueId());
        debugViewers.remove(e.getPlayer().getUniqueId());
    }

    private String buildMessage(SignChangeEvent e) {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String line = e.getLine(i);
            if (line != null && !line.isBlank()) {
                lines.add(colorizeMessage(line));
            }
        }
        return String.join("\n", lines);
    }

    private String buildMessage(String[] signLines) {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 3 && i < signLines.length; i++) {
            String line = signLines[i];
            if (line != null && !line.isBlank()) {
                lines.add(colorizeMessage(line));
            }
        }
        return String.join("\n", lines);
    }

    private String[] splitMessageForSign(String content) {
        String normalized = colorizeMessage(content).replace("\\n", "\n");
        String[] src = normalized.split("\n", -1);

        String[] out = new String[]{"", "", ""};
        for (int i = 0; i < out.length && i < src.length; i++) {
            out[i] = src[i];
        }
        return out;
    }

    private boolean giveContentSign(CommandSender sender, String content) {
        if (!sender.hasPermission("textbeacon.create")) {
            sender.sendMessage("§cこの操作を行う権限がありません (textbeacon.create)");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます");
            return true;
        }

        if (content == null || content.isBlank()) {
            player.sendMessage("§c使い方: /tb <メッセージ>");
            player.sendMessage("§7&1- &fや &#55ffff の色コードが使えます");
            player.sendMessage("§7\\nで改行できます");
            return true;
        }

        String[] lines = splitMessageForSign(content.trim());
        ItemStack item = new ItemStack(Material.OAK_SIGN, 1);
        if (!(item.getItemMeta() instanceof BlockStateMeta meta)) {
            player.sendMessage("§c看板を作成できませんでした");
            return true;
        }

        if (!(meta.getBlockState() instanceof Sign signState)) {
            player.sendMessage("§c看板を作成できませんでした");
            return true;
        }

        signState.getSide(Side.FRONT).setLine(0, "[TextBeacon]");
        signState.getSide(Side.FRONT).setLine(1, lines[0]);
        signState.getSide(Side.FRONT).setLine(2, lines[1]);
        signState.getSide(Side.FRONT).setLine(3, lines[2]);
        meta.setBlockState(signState);
        meta.getPersistentDataContainer().set(itemMessageDataKey, PersistentDataType.STRING, content.trim());
        item.setItemMeta(meta);

        HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (!overflow.isEmpty()) {
            overflow.values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
        player.sendMessage("§a取得した看板を設置してください！");
        return true;
    }

    private String colorizeMessage(String text) {
        if (text == null || text.isEmpty()) return "";

        Matcher matcher = HEX_COLOR_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(toLegacyHexColor(hex)));
        }
        matcher.appendTail(buffer);

        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    private String toLegacyHexColor(String hex) {
        StringBuilder builder = new StringBuilder("§x");
        for (char c : hex.toCharArray()) {
            builder.append('§').append(c);
        }
        return builder.toString();
    }

    private String extractItemMessage(ItemStack item) {
        if (item == null) return null;
        if (!(item.getItemMeta() instanceof BlockStateMeta meta)) return null;
        return meta.getPersistentDataContainer().get(itemMessageDataKey, PersistentDataType.STRING);
    }

    private void placeBeaconSign(BlockPlaceEvent event, Location location, String msg) {
        BlockData blockData = event.getBlockPlaced().getBlockData();
        Player player = event.getPlayer();

        Bukkit.getScheduler().runTask(this, () -> {
            Block block = location.getBlock();
            block.setBlockData(blockData, false);

            if (!(block.getState() instanceof Sign sign)) return;

            String[] lines = splitMessageForSign(msg);
            sign.getSide(Side.FRONT).setLine(0, "[TextBeacon]");
            sign.getSide(Side.FRONT).setLine(1, lines[0]);
            sign.getSide(Side.FRONT).setLine(2, lines[1]);
            sign.getSide(Side.FRONT).setLine(3, lines[2]);
            sign.update(true, false);

            player.closeInventory();
            if (!tryRegisterBeacon(player, location, msg)) {
                block.setType(Material.AIR);
            }
        });
    }

    private void startDebugTask() {
        debugTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (debugViewers.isEmpty() || beacons.isEmpty()) return;

            Iterator<UUID> it = debugViewers.iterator();
            while (it.hasNext()) {
                UUID viewerId = it.next();
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer == null || !viewer.isOnline() || !viewer.hasPermission("textbeacon.admin")) {
                    it.remove();
                    continue;
                }

                int count = 0;
                String worldName = viewer.getWorld().getName();
                for (Beacon b : beacons.values()) {
                    if (!b.world.equals(worldName)) continue;

                    Location signLoc = new Location(viewer.getWorld(),
                            b.sign.x + 0.5,
                            b.sign.y + 1.1,
                            b.sign.z + 0.5);
                    count += spawnDebugPoint(viewer, signLoc, DEBUG_PARTICLE_CAP - count);
                    if (count >= DEBUG_PARTICLE_CAP) break;

                    for (BlockKey bk : b.blocks) {
                        if (count >= DEBUG_PARTICLE_CAP) break;

                        Location pointLoc = new Location(viewer.getWorld(),
                                bk.x + 0.5,
                                bk.y + 0.5,
                                bk.z + 0.5);

                        count += spawnDebugPoint(viewer, pointLoc, DEBUG_PARTICLE_CAP - count);
                        if (count >= DEBUG_PARTICLE_CAP) break;

                        count += spawnDebugLine(viewer, signLoc, pointLoc, DEBUG_PARTICLE_CAP - count);
                    }

                    if (count >= DEBUG_PARTICLE_CAP) break;
                }
            }
        }, 20L, 20L);
    }

    private int spawnDebugPoint(Player viewer, Location loc, int budget) {
        if (budget <= 0) return 0;
        viewer.spawnParticle(Particle.REDSTONE, loc, 1, 0, 0, 0, 0, DEBUG_RED_DUST);
        return 1;
    }

    private int spawnDebugLine(Player viewer, Location from, Location to, int budget) {
        if (budget <= 0) return 0;

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

        int steps = Math.max(1, (int) Math.ceil(length / DEBUG_LINE_STEP));
        int spawned = 0;
        for (int i = 0; i <= steps && spawned < budget; i++) {
            double t = i / (double) steps;
            double x = from.getX() + dx * t;
            double y = from.getY() + dy * t;
            double z = from.getZ() + dz * t;
            viewer.spawnParticle(Particle.REDSTONE, x, y, z, 1, 0, 0, 0, 0, DEBUG_RED_DUST);
            spawned++;
        }

        return spawned;
    }

    private boolean removeBeacon(UUID id, boolean destroyWorldBlocks) {
        Beacon b = beacons.remove(id);
        if (b == null) return false;

        signMap.remove(b.sign);
        for (BlockKey bk : b.blocks) {
            removeTrigger(bk);
        }

        if (destroyWorldBlocks) {
            destroyBeaconWorldBlocks(b);
        }

        for (Set<UUID> set : inside.values()) {
            set.remove(id);
        }

        scheduleSave();
        return true;
    }

    private void destroyBeaconWorldBlocks(Beacon b) {
        World world = Bukkit.getWorld(b.world);
        if (world == null) return;

        Block signBlock = world.getBlockAt(b.sign.x, b.sign.y, b.sign.z);
        if (signBlock.getType() != Material.AIR) {
            playBlockBreakEffect(signBlock);
            signBlock.setType(Material.AIR);
        }

        for (BlockKey bk : b.blocks) {
            Block block = world.getBlockAt(bk.x, bk.y, bk.z);
            if (block.getType() == Material.AIR) continue;

            playBlockBreakEffect(block);
            block.setType(Material.AIR);
        }
    }

    private void playBlockBreakEffect(Block block) {
        World world = block.getWorld();
        if (world == null) return;

        world.playEffect(block.getLocation(), Effect.STEP_SOUND, block.getType());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("tb")) return false;

        if (args.length == 0) {
            if (sender.hasPermission("textbeacon.create")) {
                sender.sendMessage("§e/tb <message>");
                sender.sendMessage("§7改行は \\n で指定できます");
            }
            if (sender instanceof Player) {
                sender.sendMessage("§e/tb on");
                sender.sendMessage("§e/tb off");
            }
            if (sender.hasPermission("textbeacon.admin")) {
                sender.sendMessage("§e/tb list");
                sender.sendMessage("§e/tb list owner:<name>");
                sender.sendMessage("§e/tb info <uuid>");
                sender.sendMessage("§e/tb remove <uuid>");
                sender.sendMessage("§e/tb show");
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "on" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます");
                    return true;
                }
                mutedPlayers.remove(player.getUniqueId());
                inside.remove(player.getUniqueId());
                player.sendMessage("§aTextBeaconの受信をONにしました");
                scheduleSave();
                return true;
            }
            case "off" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます");
                    return true;
                }
                mutedPlayers.add(player.getUniqueId());
                inside.remove(player.getUniqueId());
                player.sendMessage("§7TextBeaconの受信をOFFにしました");
                scheduleSave();
                return true;
            }
            case "list" -> {
                if (!sender.hasPermission("textbeacon.admin")) {
                    sender.sendMessage("§cこの操作を行う権限がありません (textbeacon.admin)");
                    return true;
                }
                if (beacons.isEmpty()) {
                    sender.sendMessage("§7登録されているBeaconはありません");
                    return true;
                }

                String ownerFilter = null;
                if (args.length >= 2) {
                    String filterArg = args[1];
                    if (!filterArg.toLowerCase(Locale.ROOT).startsWith("owner:")) {
                        sender.sendMessage("§c使い方: /tb list [owner:<name>]");
                        return true;
                    }

                    ownerFilter = filterArg.substring("owner:".length()).trim();
                    if (ownerFilter.isEmpty()) {
                        sender.sendMessage("§cowner名を指定してください (例: /tb list owner:Steve)");
                        return true;
                    }
                }

                final String ownerFilterFinal = ownerFilter;

                List<Beacon> list = beacons.values().stream()
                        .filter(b -> ownerFilterFinal == null || ownerFilterFinal.equalsIgnoreCase(resolveOwnerName(b)))
                        .collect(Collectors.toList());

                if (sender instanceof Player player) {
                    String playerWorld = player.getWorld().getName();
                    Location playerLoc = player.getLocation();

                    list = list.stream()
                        .filter(b -> b.world.equals(playerWorld))
                        .sorted(Comparator.comparingDouble(b -> distanceSquared(playerLoc, b.sign)))
                        .limit(10)
                        .collect(Collectors.toList());
                }

                if (list.isEmpty()) {
                    sender.sendMessage("§7条件に一致するBeaconは見つかりませんでした");
                    return true;
                }

                String titleBase = ownerFilter == null
                    ? "§aTextBeacon一覧"
                    : "§aTextBeacon一覧(owner:" + ownerFilter + ")";
                String title = sender instanceof Player
                    ? titleBase + " §7(現在ワールドの最寄り10件) §f" + list.size() + "件"
                    : titleBase + ": " + list.size() + "件";
                sender.sendMessage(title);

                for (Beacon b : list) {
                    sender.sendMessage("§f" + b.id
                            + " §7[" + b.world + " " + b.sign.x + "," + b.sign.y + "," + b.sign.z + "]"
                            + " §bowner=" + resolveOwnerName(b)
                            + " §bblocks=" + b.blocks.size());
                }
                return true;
            }
            case "info" -> {
                if (!sender.hasPermission("textbeacon.admin")) {
                    sender.sendMessage("§cこの操作を行う権限がありません (textbeacon.admin)");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§c使い方: /tb info <uuid>");
                    return true;
                }

                UUID id;
                try {
                    id = UUID.fromString(args[1]);
                } catch (IllegalArgumentException ex) {
                    sender.sendMessage("§cUUIDの形式が正しくありません");
                    return true;
                }

                Beacon b = beacons.get(id);
                if (b == null) {
                    sender.sendMessage("§cそのIDのBeaconは見つかりませんでした");
                    return true;
                }

                sender.sendMessage("§aID: §f" + b.id);
                sender.sendMessage("§aWorld: §f" + b.world);
                sender.sendMessage("§aSign: §f" + b.sign.x + "," + b.sign.y + "," + b.sign.z);
                sender.sendMessage("§aOwner: §f" + resolveOwnerName(b) + " §7(" + (b.owner == null ? "unknown" : b.owner) + ")");
                sender.sendMessage("§aBlocks: §f" + b.blocks.size());
                sender.sendMessage("§aMessage:");
                for (String line : b.message.split("\\n")) {
                    sender.sendMessage("§f" + line);
                }
                return true;
            }
            case "remove" -> {
                if (!sender.hasPermission("textbeacon.admin")) {
                    sender.sendMessage("§cこの操作を行う権限がありません (textbeacon.admin)");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§c使い方: /tb remove <uuid>");
                    return true;
                }

                UUID id;
                try {
                    id = UUID.fromString(args[1]);
                } catch (IllegalArgumentException ex) {
                    sender.sendMessage("§cUUIDの形式が正しくありません");
                    return true;
                }

                if (!removeBeacon(id, true)) {
                    sender.sendMessage("§cそのIDのBeaconは見つかりませんでした");
                    return true;
                }

                sender.sendMessage("§eTextBeaconを削除しました: " + id);
                return true;
            }
            case "show" -> {
                if (!sender.hasPermission("textbeacon.admin")) {
                    sender.sendMessage("§cこの操作を行う権限がありません (textbeacon.admin)");
                    return true;
                }
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます");
                    return true;
                }

                UUID uid = p.getUniqueId();
                if (debugViewers.contains(uid)) {
                    debugViewers.remove(uid);
                    p.sendMessage("§7TextBeaconの可視化:OFF");
                } else {
                    debugViewers.add(uid);
                    p.sendMessage("§aTextBeaconの可視化:ON");
                }
                return true;
            }
            default -> {
                String content = String.join(" ", args).trim();
                return giveContentSign(sender, content);
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("tb")) return Collections.emptyList();

        if (args.length == 1) {
            List<String> roots = new ArrayList<>();
            if (sender instanceof Player) {
                roots.add("on");
                roots.add("off");
            }
            if (sender.hasPermission("textbeacon.admin")) {
                roots.add("list");
                roots.add("info");
                roots.add("remove");
                roots.add("show");
            }

            return roots.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }

        if (!sender.hasPermission("textbeacon.admin")) return Collections.emptyList();

        if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            if (!"owner:".startsWith(prefix) && !prefix.startsWith("owner:")) {
                return Collections.singletonList("owner:");
            }

            String namePrefix = prefix.startsWith("owner:") ? prefix.substring("owner:".length()) : "";
            List<String> names = beacons.values().stream()
                    .map(this::resolveOwnerName)
                    .filter(name -> !name.equals("(unknown)"))
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(namePrefix))
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .limit(20)
                    .map(name -> "owner:" + name)
                    .collect(Collectors.toList());

            if (names.isEmpty()) {
                return Collections.singletonList("owner:");
            }
            return names;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("remove"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return beacons.keySet().stream()
                    .map(UUID::toString)
                    .filter(id -> id.startsWith(prefix))
                    .limit(20)
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }

    private String resolveOwnerName(Beacon b) {
        if (b.ownerName != null && !b.ownerName.isBlank()) {
            return b.ownerName;
        }

        if (b.owner != null) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(b.owner);
            String name = player.getName();
            if (name != null && !name.isBlank()) {
                b.ownerName = name;
                return name;
            }
            return b.owner.toString();
        }

        return "(unknown)";
    }

    private double distanceSquared(Location origin, BlockKey key) {
        double dx = (key.x + 0.5) - origin.getX();
        double dy = (key.y + 0.5) - origin.getY();
        double dz = (key.z + 0.5) - origin.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    // =========================
    // ガラス検出
    // =========================
    private Block findNearestGlassInRadius(Location center, int radius) {
        World w = center.getWorld();
        if (w == null) return null;

        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        int bestDist = Integer.MAX_VALUE;
        Block best = null;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block b = w.getBlockAt(cx + x, cy + y, cz + z);
                    if (b.getType() != Material.LIGHT_BLUE_STAINED_GLASS) continue;

                    int dist = x * x + y * y + z * z;

                    if (dist < bestDist) {
                        bestDist = dist;
                        best = b;
                    }
                }
            }
        }

        return best;
    }

    private List<Location> collectConnectedGlass(Block seed) {
        int minX = seed.getX(), minY = seed.getY(), minZ = seed.getZ();
        int maxX = seed.getX(), maxY = seed.getY(), maxZ = seed.getZ();

        List<Location> list = new ArrayList<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();

        queue.add(seed);
        visited.add(pack(seed.getX(), seed.getY(), seed.getZ()));

        while (!queue.isEmpty()) {
            Block current = queue.poll();
            if (current.getType() != Material.LIGHT_BLUE_STAINED_GLASS) continue;

            int x = current.getX();
            int y = current.getY();
            int z = current.getZ();

            list.add(current.getLocation());

            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);

            if ((maxX - minX > MAX_CUBE_SIZE) ||
                (maxY - minY > MAX_CUBE_SIZE) ||
                (maxZ - minZ > MAX_CUBE_SIZE)) {
                return null;
            }

            for (BlockFace face : ADJACENT_FACES) {
                Block next = current.getRelative(face);
                if (next.getType() != Material.LIGHT_BLUE_STAINED_GLASS) continue;

                long key = pack(next.getX(), next.getY(), next.getZ());
                if (visited.add(key)) {
                    queue.add(next);
                }
            }
        }

        return list;
    }

    private boolean isWithin5Cube(List<Location> locs) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (Location l : locs) {
            minX = Math.min(minX, l.getBlockX());
            minY = Math.min(minY, l.getBlockY());
            minZ = Math.min(minZ, l.getBlockZ());
            maxX = Math.max(maxX, l.getBlockX());
            maxY = Math.max(maxY, l.getBlockY());
            maxZ = Math.max(maxZ, l.getBlockZ());
        }

         return (maxX - minX <= MAX_CUBE_SIZE) &&
             (maxY - minY <= MAX_CUBE_SIZE) &&
             (maxZ - minZ <= MAX_CUBE_SIZE);
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (z & 0x3FFFFFF) << 12)
                | (y & 0xFFF);
    }

    private void putTrigger(BlockKey key, UUID beaconId) {
        triggerMap.computeIfAbsent(key.world, w -> new HashMap<>())
                .put(pack(key.x, key.y, key.z), beaconId);
    }

    private void removeTrigger(BlockKey key) {
        Map<Long, UUID> worldMap = triggerMap.get(key.world);
        if (worldMap == null) return;

        worldMap.remove(pack(key.x, key.y, key.z));
        if (worldMap.isEmpty()) {
            triggerMap.remove(key.world);
        }
    }

    private UUID getTrigger(String world, int x, int y, int z) {
        Map<Long, UUID> worldMap = triggerMap.get(world);
        if (worldMap == null) return null;
        return worldMap.get(pack(x, y, z));
    }

    private UUID getTriggeredBeaconId(Player player, Location to) {
        Location current = player.getLocation();
        BoundingBox box = player.getBoundingBox().shift(
                to.getX() - current.getX(),
                to.getY() - current.getY(),
                to.getZ() - current.getZ()
        );

        String world = to.getWorld().getName();
        int minX = (int) Math.floor(box.getMinX());
        int minY = (int) Math.floor(box.getMinY());
        int minZ = (int) Math.floor(box.getMinZ());
        int maxX = (int) Math.floor(box.getMaxX());
        int maxY = (int) Math.floor(box.getMaxY()) + UPPER_BODY_BLOCK_OFFSET;
        int maxZ = (int) Math.floor(box.getMaxZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    UUID id = getTrigger(world, x, y, z);
                    if (id != null) return id;
                }
            }
        }

        return null;
    }

    // =========================
    // 保存（遅延）
    // =========================
    private void scheduleSave() {
        if (saveScheduled) return;
        saveScheduled = true;

        Bukkit.getScheduler().runTaskLater(this, () -> {
            saveNow();
            saveScheduled = false;
        }, 100L); // 5秒
    }

    private void saveNow() {
        yaml = new YamlConfiguration();

        List<String> mutedList = mutedPlayers.stream()
                .map(UUID::toString)
                .sorted()
                .collect(Collectors.toList());
        yaml.set("settings.mutedPlayers", mutedList);

        for (Beacon b : beacons.values()) {
            String path = "beacons." + b.id;

            yaml.set(path + ".world", b.world);
            yaml.set(path + ".msg", b.message);
            yaml.set(path + ".owner.uuid", b.owner == null ? null : b.owner.toString());
            yaml.set(path + ".owner.name", b.ownerName);

            yaml.set(path + ".sign.x", b.sign.x);
            yaml.set(path + ".sign.y", b.sign.y);
            yaml.set(path + ".sign.z", b.sign.z);

            List<String> list = new ArrayList<>();
            for (BlockKey k : b.blocks) {
                list.add(k.x + "," + k.y + "," + k.z);
            }
            yaml.set(path + ".blocks", list);
        }

        try {
            yaml.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void load() {
        if (!file.exists()) return;

        yaml = YamlConfiguration.loadConfiguration(file);

        for (String s : yaml.getStringList("settings.mutedPlayers")) {
            try {
                mutedPlayers.add(UUID.fromString(s));
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Invalid muted player UUID skipped: " + s);
            }
        }

        if (!yaml.contains("beacons")) return;

        org.bukkit.configuration.ConfigurationSection section = yaml.getConfigurationSection("beacons");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Invalid beacon UUID in data.yml: " + key);
                continue;
            }

            String path = "beacons." + key;
            String world = yaml.getString(path + ".world");
            if (world == null || world.isBlank()) {
                getLogger().warning("Beacon skipped due to missing world: " + key);
                continue;
            }

            String msg = yaml.getString(path + ".msg", "");

            int sx = yaml.getInt(path + ".sign.x");
            int sy = yaml.getInt(path + ".sign.y");
            int sz = yaml.getInt(path + ".sign.z");

            UUID owner = null;
            String ownerRaw = yaml.getString(path + ".owner.uuid");
            if (ownerRaw != null && !ownerRaw.isBlank()) {
                try {
                    owner = UUID.fromString(ownerRaw);
                } catch (IllegalArgumentException ex) {
                    getLogger().warning("Invalid owner UUID skipped for beacon " + key + ": " + ownerRaw);
                }
            }
            String ownerName = yaml.getString(path + ".owner.name");

            Beacon b = new Beacon(id, new BlockKey(world, sx, sy, sz), msg, owner, ownerName);
            signMap.put(b.sign, id);

            for (String s : yaml.getStringList(path + ".blocks")) {
                String[] sp = s.split(",");
                if (sp.length != 3) {
                    getLogger().warning("Invalid block entry skipped for beacon " + key + ": " + s);
                    continue;
                }

                try {
                    BlockKey bk = new BlockKey(world,
                            Integer.parseInt(sp[0]),
                            Integer.parseInt(sp[1]),
                            Integer.parseInt(sp[2]));

                    b.blocks.add(bk);
                    putTrigger(bk, id);
                } catch (NumberFormatException ex) {
                    getLogger().warning("Invalid block coordinates skipped for beacon " + key + ": " + s);
                }
            }

            beacons.put(id, b);
        }
    }

    private int pruneMissingSignBeacons() {
        List<UUID> invalid = new ArrayList<>();

        for (Beacon b : beacons.values()) {
            World world = Bukkit.getWorld(b.world);
            if (world == null) {
                invalid.add(b.id);
                continue;
            }

            Block block = world.getBlockAt(b.sign.x, b.sign.y, b.sign.z);
            if (!(block.getState() instanceof Sign)) {
                invalid.add(b.id);
            }
        }

        for (UUID id : invalid) {
            removeBeaconWithoutSave(id);
        }

        return invalid.size();
    }

    private void removeBeaconWithoutSave(UUID id) {
        Beacon b = beacons.remove(id);
        if (b == null) return;

        signMap.remove(b.sign);
        for (BlockKey bk : b.blocks) {
            removeTrigger(bk);
        }

        for (Set<UUID> set : inside.values()) {
            set.remove(id);
        }
    }

    // =========================
    // クラス
    // =========================
    static class Beacon {
        UUID id;
        String world;
        BlockKey sign;
        String message;
        UUID owner;
        String ownerName;
        List<BlockKey> blocks = new ArrayList<>();

        Beacon(UUID id, Location signLoc, String message, UUID owner, String ownerName) {
            this(id,
                    new BlockKey(signLoc),
                    message,
                    owner,
                    ownerName);
        }

        Beacon(UUID id, BlockKey sign, String message, UUID owner, String ownerName) {
            this.id = id;
            this.world = sign.world;
            this.sign = sign;
            this.message = message;
            this.owner = owner;
            this.ownerName = ownerName;
        }
    }

    static class BlockKey {
        final String world;
        final int x, y, z;

        BlockKey(Location loc) {
            this(loc.getWorld().getName(),
                    loc.getBlockX(),
                    loc.getBlockY(),
                    loc.getBlockZ());
        }

        BlockKey(String world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof BlockKey k)) return false;
            return x == k.x && y == k.y && z == k.z && world.equals(k.world);
        }

        @Override
        public int hashCode() {
            return Objects.hash(world, x, y, z);
        }
    }
}