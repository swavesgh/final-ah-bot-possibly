package com.snipe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import java.util.*;
import java.util.regex.*;

public class Sniper {
    enum State { OPEN, SCAN, CONFIRM, WAIT, RS_FIND, RS_SEND, RS_CONFIRM }
    public static boolean running = false;
    public static String status = "idle";
    static boolean analyzing = false;   // first pass before sniping: scan only
    static boolean analyzeOnly = false; // one scan pass, then stop
    static State state = State.OPEN;
    static int cooldown, wait, page, tries;
    static Map<Integer, List<Market.Listing>> pages = new HashMap<>();
    static Map<String, Long> alerted = new HashMap<>();
    static Purchases.P pending;
    static double resellRef;
    static Pattern priceP, sellerP;

    static void begin() {
        priceP = Pattern.compile(Config.I.priceRegex);
        sellerP = Pattern.compile(Config.I.sellerRegex);
        running = true; state = State.OPEN; cooldown = 0;
    }

    public static void start() {
        begin();
        analyzeOnly = false;
        analyzing = Config.I.buyEnabled && Config.I.analyzeFirst && System.currentTimeMillis() - Market.lastCommit > 10 * 60_000L;
        status = "starting";
        msg(!Config.I.buyEnabled ? "Tracking started (buy mode is off)" : analyzing ? "Analyzing the AH first, then sniping" : "Sniping started");
    }
    /** One full scan with no buying, then stops by itself. */
    public static void analyze() {
        if (running) { msg("Stop the bot first"); return; }
        begin(); analyzeOnly = true; analyzing = false; status = "starting analysis";
        msg("Analyzing the AH (no buying)");
    }
    public static void stop() { running = false; analyzing = false; analyzeOnly = false; status = "idle"; msg("Stopped"); Market.save(); }

    static void msg(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) Compat.say(mc, "[Sniper] " + s);
    }

    static Market.Listing listing(ItemStack st) {
        ItemLore lore = st.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        double total = -1; String seller = "?";
        for (Component line : lore.lines()) {
            String s = line.getString();
            if (total < 0) {
                Matcher m = priceP.matcher(s);
                if (m.find()) {
                    try { total = Double.parseDouble(m.group(1).replace(",", "")) * Config.suffix(m.groupCount() >= 2 && m.group(2) != null ? m.group(2) : ""); }
                    catch (Exception ignored) {}
                }
            }
            if (seller.equals("?")) { Matcher sm = sellerP.matcher(s); if (sm.find()) seller = sm.group(1); }
        }
        if (total <= 0) return null;
        String name = st.getHoverName().getString();
        String id = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        return new Market.Listing(Config.key(name), name, id, st.has(DataComponents.CUSTOM_NAME), total, st.getCount(), seller);
    }

    static int findButton(ChestMenu h, String contains) {
        int size = h.getRowCount() * 9;
        for (int i = 0; i < size; i++) {
            ItemStack s = h.getSlot(i).getItem();
            if (!s.isEmpty() && s.getHoverName().getString().toLowerCase().contains(contains.toLowerCase())) return i;
        }
        return -1;
    }

    static void click(Minecraft mc, ChestMenu h, int slot) { Compat.click(mc, h, slot); cooldown = Config.I.delayTicks; }

    /** Chat alert for a listing far below the average of an item you are not sniping. */
    static void alertIfGood(Market.Listing l, int pageNo) {
        Config.Entry e = Config.I.entries.get(l.key());
        if (e != null && e.enabled) return;
        Market.Snap s = Market.latest(l.key());
        if (s == null || s.total < 3 || s.avg < Config.I.alertMinPrice) return;
        double disc = 1 - l.unit() / s.avg;
        if (disc * 100 < Config.I.alertPercent) return;
        String sig = l.key() + "|" + l.seller() + "|" + (long) l.total() + "|" + l.count();
        long now = System.currentTimeMillis();
        Long t = alerted.get(sig);
        if (t != null && now - t < 30 * 60_000L) return;
        if (alerted.size() > 500) alerted.clear();
        alerted.put(sig, now);
        msg("GOOD DEAL: " + l.name() + " x" + l.count() + " for " + Config.fmt(l.total()) + " (" + Math.round(disc * 100)
            + "% under avg " + Config.fmt(s.avg) + ") by " + l.seller() + " - /" + Config.I.command + " page " + (pageNo + 1));
    }

    /** Price to relist at (total for the stack), 0 = no data, -1 = not profitable. */
    static double resellPrice() {
        Market.Snap s = Market.latest(pending.key);
        double ref = switch (Config.I.resellMode) {
            case "AVERAGE" -> s != null ? s.avg : 0;
            case "MEDIAN" -> s != null ? s.median : 0;
            default -> resellRef > 0 ? resellRef : (s != null ? s.median : 0);
        };
        if (ref <= 0) return 0;
        double total = Math.floor(ref * (1 - Config.I.undercutPercent / 100) * pending.count);
        double net = total * (1 - Config.I.ahTaxPercent / 100);
        return net < pending.total * (1 + Config.I.minProfitPercent / 100) ? -1 : total;
    }

    static void bought(Minecraft mc) {
        Purchases.add(pending);
        if (Config.I.resell) { state = State.RS_FIND; tries = 0; cooldown = Config.I.resellWaitTicks; status = "Preparing resell"; }
        else { state = State.SCAN; cooldown += 8; }
    }

    public static void tick(Minecraft mc) {
        if (!running || mc.player == null || mc.getConnection() == null) return;
        if (cooldown > 0) { cooldown--; return; }

        switch (state) {
            case OPEN -> {
                pages.clear(); page = 0; wait = 0; status = "Opening AH";
                Compat.command(mc, Config.I.command);
                state = State.SCAN; cooldown = Config.I.delayTicks * 2;
            }
            case SCAN -> {
                if (!(Compat.screen(mc) instanceof ContainerScreen gs)) { if (++wait > 80) state = State.OPEN; return; }
                wait = 0;
                ChestMenu h = gs.getMenu();
                int size = h.getRowCount() * 9;
                List<Market.Listing> list = new ArrayList<>();
                List<Integer> slots = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    ItemStack st = h.getSlot(i).getItem();
                    if (st.isEmpty()) continue;
                    Market.Listing l = listing(st);
                    if (l != null) { list.add(l); slots.add(i); }
                }
                pages.put(page, list);
                int seen = 0; for (var p : pages.values()) seen += p.size();
                String mode = analyzeOnly || analyzing ? "Analyzing" : Config.I.buyEnabled ? "Sniping" : "Tracking";
                status = mode + " - page " + (page + 1) + ", " + seen + " listings";

                if (Config.I.alerts) for (Market.Listing l : list) alertIfGood(l, page);

                boolean buyNow = Config.I.buyEnabled && !analyzing && !analyzeOnly;
                if (buyNow) {
                    int best = -1; double bestUnit = Double.MAX_VALUE;
                    for (int j = 0; j < list.size(); j++) {
                        Market.Listing l = list.get(j);
                        Config.Entry e = Config.I.entries.get(l.key());
                        if (e == null || !e.enabled || e.maxPrice <= 0) continue;
                        if ((e.each ? l.unit() : l.total()) <= e.maxPrice && l.unit() < bestUnit) { best = j; bestUnit = l.unit(); }
                    }
                    if (best >= 0) {
                        Market.Listing l = list.get(best);
                        resellRef = 0;
                        for (int j = 0; j < list.size(); j++)
                            if (j != best && list.get(j).key().equals(l.key()) && (resellRef == 0 || list.get(j).unit() < resellRef)) resellRef = list.get(j).unit();
                        pending = new Purchases.P();
                        pending.name = l.name(); pending.key = l.key(); pending.count = l.count(); pending.total = l.total(); pending.time = System.currentTimeMillis();
                        msg("Buying " + l.name() + " x" + l.count() + " for " + Config.fmt(l.total()));
                        click(mc, h, slots.get(best));
                        state = State.CONFIRM; wait = 0;
                        return;
                    }
                }
                int next = findButton(h, Config.I.nextName);
                if (next >= 0 && page < Config.I.maxPages) { page++; click(mc, h, next); cooldown += 4; return; }

                List<Market.Listing> all = new ArrayList<>();
                for (var p : pages.values()) all.addAll(p);
                Market.commit(all);
                mc.player.closeContainer();
                if (analyzeOnly) {
                    running = false; analyzeOnly = false;
                    status = "Analysis done (" + all.size() + " listings)";
                    msg("Analysis complete: " + all.size() + " listings, " + Market.d.latest.size() + " items with price data");
                    Market.save();
                    return;
                }
                if (analyzing) { analyzing = false; msg("Analysis complete - sniping now"); }
                state = State.WAIT; cooldown = Config.I.refreshTicks; status = "Scan done (" + all.size() + " listings)";
            }
            case CONFIRM -> {
                if (Compat.screen(mc) instanceof ContainerScreen gs) {
                    int c = findButton(gs.getMenu(), Config.I.confirmName);
                    if (c >= 0) { click(mc, gs.getMenu(), c); bought(mc); return; }
                }
                if (++wait > 20) bought(mc);
            }
            case WAIT -> state = State.OPEN;

            case RS_FIND -> {
                if (Compat.screen(mc) != null) mc.player.closeContainer();
                int found = -1;
                for (int i = 0; i < 36 && found < 0; i++) {
                    ItemStack s = Compat.invItem(mc, i);
                    if (!s.isEmpty() && s.getHoverName().getString().equalsIgnoreCase(pending.name)) found = i;
                }
                if (found < 0) {
                    if (++tries > 10) { pending.status = "bought (not found in inventory)"; Purchases.save(); state = State.OPEN; }
                    else cooldown = 10;
                    return;
                }
                double price = resellPrice();
                if (price <= 0) {
                    pending.status = price < 0 ? "held (resell not profitable)" : "held (no price data)";
                    Purchases.save(); msg(pending.name + ": " + pending.status); state = State.OPEN; return;
                }
                pending.listedFor = price;
                Compat.holdSlot(mc, found);
                state = State.RS_SEND; cooldown = 6; status = "Relisting " + pending.name;
            }
            case RS_SEND -> {
                Compat.command(mc, Config.I.sellCommand.replace("{price}", String.valueOf((long) pending.listedFor)));
                pending.status = "listed"; Purchases.save();
                msg("Relisting " + pending.name + " for " + Config.fmt(pending.listedFor));
                state = State.RS_CONFIRM; wait = 0; cooldown = Config.I.delayTicks * 3;
            }
            case RS_CONFIRM -> {
                if (Compat.screen(mc) instanceof ContainerScreen gs) {
                    int c = findButton(gs.getMenu(), Config.I.confirmName);
                    if (c >= 0) { click(mc, gs.getMenu(), c); cooldown += 10; state = State.OPEN; return; }
                }
                if (++wait > 20) state = State.OPEN;
            }
        }
    }
}
