package com.zhichaoxi.applied_schematicannon.me;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import appeng.blockentity.misc.InterfaceBlockEntity;
import appeng.core.definitions.AEItems;
import appeng.helpers.InterfaceLogic;
import com.google.common.collect.ImmutableSet;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.LongConsumer;

/**
 * Bridges a Schematicannon to the ME network through the ME Interfaces attached to it.
 * <p>
 * Storage lookups and item extraction go through the grid's {@link MEStorage}. Missing items are synthesized through
 * AE2's official auto-crafting API ({@link ICraftingService#beginCraftingCalculation}).
 * <p>
 * Every job is submitted with a {@link CraftingRequester}. That part is essential: AE2 only delivers a job's final
 * output to a requester and throws the output away when a job has none, so a requester-less ("standalone") job would
 * craft the items and destroy them. The requester here stores the crafted items back into the ME network, from where
 * the cannon takes them on a later tick like any other stored item.
 */
public final class MEInterfaceHelper {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * How long a request is not retried after it failed. Prevents an unfulfillable request (no pattern, no CPU, no
     * ingredients) from rebuilding the crafting tree on every single tick.
     */
    public static final int FAILURE_COOLDOWN_TICKS = 60;

    private MEInterfaceHelper() {
    }

    /**
     * The requests a single Schematicannon is currently waiting on.
     * <p>
     * Crafting calculations run on AE2's crafting thread pool, so their {@link Future} is polled from the cannon's tick
     * method instead of being awaited on the game thread.
     */
    public static final class PendingCraftingJobs {
        private final List<Entry> entries = new ArrayList<>();

        public boolean isEmpty() {
            return entries.isEmpty();
        }

        public int size() {
            return entries.size();
        }

        /**
         * @return the request for {@code what}, or {@code null} if this item is not being crafted right now.
         */
        @Nullable
        public Entry get(AEKey what) {
            for (Entry entry : entries) {
                if (entry.stack().what().equals(what)) {
                    return entry;
                }
            }
            return null;
        }

        public void add(Entry entry) {
            entries.add(entry);
        }

        private void set(int index, Entry entry) {
            entries.set(index, entry);
        }

        /**
         * Drops every pending request. Calculations that are still running are cancelled; jobs a crafting CPU already
         * accepted keep running and deliver into the ME network on their own.
         */
        public void clear() {
            for (Entry entry : entries) {
                entry.plan().cancel(false);
                entry.requester().cancel();
            }
            entries.clear();
        }
    }

    /**
     * One queued crafting request.
     *
     * @param stack       what is being crafted
     * @param plan        the pending crafting calculation
     * @param requester   receives the job's output and stores it in the ME network
     * @param cooldown    ticks to wait before retrying after a failure
     * @param reservation how many items the request is expected to add to the ME network
     */
    public record Entry(GenericStack stack, Future<ICraftingPlan> plan, CraftingRequester requester, int cooldown,
                        long reservation) {
        public Entry withCooldown(int newCooldown) {
            return new Entry(stack, plan, requester, newCooldown, reservation);
        }
    }

    /**
     * Receives the output of the jobs this cannon submitted and puts it back into the ME network.
     * <p>
     * AE2 asks a requester for its links whenever its grid node is (re)added, so the links are kept for the lifetime of
     * the request.
     */
    public static final class CraftingRequester implements ICraftingRequester {
        private final IGrid grid;
        private final IActionHost host;
        private final List<ICraftingLink> links = new ArrayList<>();

        public CraftingRequester(IGrid grid, @Nullable IActionHost host) {
            this.grid = grid;
            this.host = host;
        }

        public void track(@Nullable ICraftingLink link) {
            if (link != null) {
                links.add(link);
            }
        }

        public void cancel() {
            for (ICraftingLink link : links) {
                link.cancel();
            }
            links.clear();
        }

        /**
         * @return the source to submit jobs with. Reporting the interface as the acting machine keeps the request
         * meaningful to AE2's security station, if one is installed.
         */
        public IActionSource source() {
            return host == null ? IActionSource.empty() : IActionSource.ofMachine(host);
        }

        @Override
        public ImmutableSet<ICraftingLink> getRequestedJobs() {
            return ImmutableSet.copyOf(links);
        }

        @Override
        public long insertCraftedItems(ICraftingLink link, AEKey what, long amount, Actionable mode) {
            // Never swallow the output: AE2 discards whatever a requester does not accept.
            return grid.getStorageService().getInventory().insert(what, amount, mode, IActionSource.empty());
        }

        @Override
        public void jobStateChange(ICraftingLink link) {
            links.remove(link);
        }

        @Override
        public IGridNode getActionableNode() {
            return host == null ? null : host.getActionableNode();
        }
    }

    /**
     * Iterates the distinct grids reachable through the attached interfaces. Each grid is only reported once, so
     * several interfaces attached to the same network are never counted twice.
     */
    private static List<IGrid> gridsOf(List<InterfaceBlockEntity> interfaces) {
        List<IGrid> grids = new ArrayList<>(interfaces.size());
        for (InterfaceBlockEntity iface : interfaces) {
            IGrid grid = gridOf(iface);
            if (grid != null && !grids.contains(grid)) {
                grids.add(grid);
            }
        }
        return grids;
    }

    /**
     * Simulates extracting {@code what} from the network. Never mutates the network.
     */
    public static long simulateExtract(List<InterfaceBlockEntity> interfaces, AEKey what, long amount,
                                       IActionSource source) {
        long found = 0;
        for (IGrid grid : gridsOf(interfaces)) {
            found += grid.getStorageService().getInventory()
                    .extract(what, amount - found, Actionable.SIMULATE, source);
            if (found >= amount) {
                break;
            }
        }
        return found;
    }

    /**
     * Extracts {@code amount} of {@code what} from the network.
     * <p>
     * Every grid re-simulates the remaining amount before it commits an extraction, so a cannon attached to several
     * grids never pulls more than the requirement asked for.
     *
     * @return the amount that was actually extracted.
     */
    public static long extract(List<InterfaceBlockEntity> interfaces, AEKey what, long amount,
                               IActionSource source) {
        if (amount <= 0) {
            return 0;
        }

        long stillNeeded = amount;
        for (IGrid grid : gridsOf(interfaces)) {
            MEStorage storage = grid.getStorageService().getInventory();
            long available = storage.extract(what, stillNeeded, Actionable.SIMULATE, source);
            if (available <= 0) {
                continue;
            }

            stillNeeded -= storage.extract(what, Math.min(available, stillNeeded), Actionable.MODULATE, source);
            if (stillNeeded <= 0) {
                break;
            }
        }
        return amount - stillNeeded;
    }

    /**
     * Requests that the attached ME network synthesizes {@code missing} items of {@code what}.
     * <p>
     * A new calculation is only started when nothing is queued for this key and the network is not already crafting
     * enough of it. Auto-crafting needs to be armed by an ME Interface with a Crafting Card, so a cannon never
     * silently starts jobs on a network its owner did not set up for it.
     *
     * @param host     the machine the request is made through. It must resolve to an actionable grid node, otherwise
     *                 AE2's crafting tree refuses to look for patterns and reports the requested item as unavailable.
     * @param missing  how many items the cannon is missing right now
     * @param onBlocked called with the amount a stalled job is already crafting when the request is suppressed because
     *                 the network is already working on it. Reporting is left to the caller so it can throttle: this
     *                 method runs every tick.
     * @return {@code true} if a job for this key is queued or already running on a crafting CPU.
     */
    public static boolean requestCrafting(List<InterfaceBlockEntity> interfaces, Level level,
                                          PendingCraftingJobs pending, AEKey what, long missing,
                                          @Nullable IActionHost host, LongConsumer onBlocked) {
        if (missing <= 0 || level == null || level.isClientSide()) {
            return false;
        }

        ItemStack itemStack = asItemStack(what);
        GenericStack stack = itemStack == null ? null : GenericStack.fromItemStack(itemStack);
        if (stack == null) {
            return false;
        }

        int interfacesOnGrids = 0;
        boolean craftingArmed = false;

        for (IGrid grid : gridsOf(interfaces)) {
            interfacesOnGrids++;
            ICraftingService crafting = grid.getCraftingService();

            // AE2 tracks what every crafting CPU on the grid is already working on, so this key must not be requested
            // a second time while the items are on their way. A stalled job keeps this true forever, which looks
            // exactly like the cannon refusing to order anything, so it is reported rather than silently ignored.
            long alreadyCrafting = crafting.getRequestedAmount(what);
            if (alreadyCrafting > 0 || crafting.isRequesting(what)) {
                onBlocked.accept(alreadyCrafting);
                return true;
            }

            // A calculation that is still running already is the request for this key; starting another one every tick
            // would pile up crafting simulations on AE2's crafting thread pool. A request stuck in this state is the
            // other way the cannon can look like it ignores a network that has the ingredients.
            if (pending.get(what) != null) {
                onBlocked.accept(-1);
                return true;
            }

            if (!hasCraftingCardOn(grid, interfaces)) {
                LOGGER.debug("No adjacent ME Interface with a Crafting Card, so {} is not requested", describe(stack));
                continue;
            }

            // Without a pattern there is nothing to request; skipping the calculation keeps AE2's crafting thread pool
            // free instead of re-planning an impossible job on a loop.
            if (!crafting.isCraftable(what)) {
                LOGGER.debug("The ME network has no pattern for {}", describe(stack));
                continue;
            }
            craftingArmed = true;

            Future<ICraftingPlan> plan = null;
            try {
                IActionSource simSource = host == null ? IActionSource.empty() : IActionSource.ofMachine(host);
                // The simulation requester has to resolve a grid node, because AE2's crafting tree only expands
                // patterns when ICraftingSimulationRequester.getGridNode() is non-null (it derives the node from the
                // action source's machine). With an empty source every pattern is silently skipped and the request
                // comes back as "the final output is missing".
                ICraftingSimulationRequester requester = () -> simSource;
                plan = crafting.beginCraftingCalculation(level, requester, what, missing,
                        CalculationStrategy.CRAFT_LESS);
            } catch (RuntimeException e) {
                LOGGER.debug("Could not start a crafting calculation for {}", describe(stack), e);
            }
            if (plan == null) {
                continue;
            }

            pending.add(new Entry(stack, plan, new CraftingRequester(grid, actionHostOf(grid, interfaces)), 0, missing));
            LOGGER.info("Requested {} x{} from the ME network", describe(stack), missing);
            return true;
        }

        return false;
    }

    /**
     * Explains why {@link #requestCrafting} could not do anything about an item, so the cannon can tell its owner once
     * instead of silently waiting forever.
     *
     * @return {@code null} when auto-crafting is properly armed and the network simply could not fulfil the request.
     */
    @Nullable
    public static String describeRequestFailure(List<InterfaceBlockEntity> interfaces, @Nullable AEKey what) {
        if (interfaces.isEmpty()) {
            return "this cannon has no adjacent ME Interface";
        }

        List<IGrid> grids = gridsOf(interfaces);
        if (grids.isEmpty()) {
            return "the adjacent ME Interface is not connected to a network";
        }

        boolean armed = false;
        boolean craftable = false;
        boolean cpuPresent = false;
        for (IGrid grid : grids) {
            if (!hasCraftingCardOn(grid, interfaces)) {
                continue;
            }

            armed = true;
            ICraftingService crafting = grid.getCraftingService();
            if (what != null && crafting.isCraftable(what)) {
                craftable = true;
            }
            if (!crafting.getCpus().isEmpty()) {
                cpuPresent = true;
            }
        }

        if (!armed) {
            return "no adjacent ME Interface has a Crafting Card installed";
        }
        if (!craftable) {
            return "the ME network has no pattern that produces this item";
        }
        if (!cpuPresent) {
            return "the ME network has no crafting CPU";
        }
        return null;
    }

    /**
     * Explains why a crafting calculation came back as a simulation, i.e. why the network cannot produce the key. When
     * AE2 could tell us what it was missing, that item list is reported too, because "no pattern", "no CPU" and
     * "ingredients missing" are all the same simulation result otherwise.
     */
    public static String explainMissing(@Nullable ICraftingPlan plan, List<InterfaceBlockEntity> interfaces,
                                        AEKey what) {
        boolean craftable = false;
        boolean cpuPresent = false;
        for (IGrid grid : gridsOf(interfaces)) {
            ICraftingService crafting = grid.getCraftingService();
            if (crafting.isCraftable(what)) {
                craftable = true;
                if (!crafting.getCpus().isEmpty()) {
                    cpuPresent = true;
                }
            }
        }

        String reason;
        if (!craftable) {
            reason = "no encoded pattern produces this item";
        } else if (!cpuPresent) {
            reason = "a pattern exists but the ME network has no crafting CPU";
        } else {
            reason = "the required ingredients are not available";
        }

        String missing = describeMissing(plan);
        String patterns = describePatterns(interfaces, what);
        String detail = missing.isEmpty() ? reason : reason + "; missing: " + missing;
        return patterns.isEmpty() ? detail : detail + "; patterns: " + patterns;
    }

    /**
     * Lists the patterns AE2 holds for an item together with their inputs. A processing pattern instead of a crafting
     * pattern, or a pattern whose inputs nothing can provide, both surface as "ingredients missing" otherwise.
     */
    private static String describePatterns(List<InterfaceBlockEntity> interfaces, AEKey what) {
        StringBuilder builder = new StringBuilder();
        for (IGrid grid : gridsOf(interfaces)) {
            for (IPatternDetails pattern : grid.getCraftingService().getCraftingFor(what)) {
                if (builder.length() > 0) {
                    builder.append(" | ");
                }
                builder.append("out=").append(describeAmounts(pattern.getOutputs())).append(" in=");
                boolean first = true;
                for (IPatternDetails.IInput input : pattern.getInputs()) {
                    GenericStack[] possible = input.getPossibleInputs();
                    if (possible.length == 0) {
                        continue;
                    }
                    if (!first) {
                        builder.append(" + ");
                    }
                    first = false;
                    builder.append(describe(possible[0])).append(" x").append(possible[0].amount());
                    if (possible.length > 1) {
                        builder.append("(+").append(possible.length - 1).append(" substitutes)");
                    }
                }
                if (first) {
                    builder.append("nothing");
                }
            }
        }
        return builder.toString();
    }

    private static String describeAmounts(List<GenericStack> stacks) {
        StringBuilder builder = new StringBuilder();
        for (GenericStack stack : stacks) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(describe(stack)).append(" x").append(stack.amount());
        }
        return builder.isEmpty() ? "nothing" : builder.toString();
    }

    /**
     * @return a readable listing of what the network currently holds, used to diagnose a failed crafting request.
     */
    public static String describeInventory(List<InterfaceBlockEntity> interfaces) {
        StringBuilder builder = new StringBuilder();
        for (IGrid grid : gridsOf(interfaces)) {
            for (var entry : grid.getStorageService().getInventory().getAvailableStacks()) {
                if (builder.length() > 0) {
                    builder.append(", ");
                }
                builder.append(describe(new GenericStack(entry.getKey(), entry.getLongValue())))
                        .append(" x").append(entry.getLongValue());
            }
        }
        return builder.isEmpty() ? "<empty>" : builder.toString();
    }

    /**
     * @return the items the calculation could not find, e.g. {@code Oak Log x1}.
     */
    private static String describeMissing(@Nullable ICraftingPlan plan) {
        if (plan == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (var entry : plan.missingItems()) {
            if (entry.getLongValue() <= 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(describe(new GenericStack(entry.getKey(), entry.getLongValue())))
                    .append(" x").append(entry.getLongValue());
        }
        return builder.toString();
    }

    /**
     * Finishes queued crafting calculations whose result is already available.
     *
     * @return the number of jobs that were handed over to a crafting CPU.
     */
    public static int pollCraftingJobs(List<InterfaceBlockEntity> interfaces, PendingCraftingJobs pending) {
        int submitted = 0;

        for (int i = 0; i < pending.entries.size(); i++) {
            Entry entry = pending.entries.get(i);

            if (entry.cooldown() > 0) {
                pending.set(i, entry.withCooldown(entry.cooldown() - 1));
                continue;
            }

            if (!entry.plan().isDone()) {
                continue;
            }

            // A simulated plan means the network cannot produce the full amount right now, for example because an
            // ingredient is missing. Retry later instead of hammering the crafting calculation pool.
            ICraftingPlan plan = resolve(entry.plan());
            if (plan == null || plan.simulation()) {
                LOGGER.info("The ME network cannot produce {} right now ({}), retrying in {} ticks",
                        describe(entry.stack()), explainMissing(plan, interfaces, entry.stack().what()),
                        FAILURE_COOLDOWN_TICKS);
                pending.set(i, entry.withCooldown(FAILURE_COOLDOWN_TICKS));
                continue;
            }

            ICraftingSubmitResult result = submit(plan, entry);
            if (result != null && result.successful()) {
                submitted++;
                LOGGER.debug("Submitted an auto-crafting job for {} x{}", describe(entry.stack()),
                        entry.reservation());
                pending.entries.remove(i--);
            } else {
                LOGGER.debug("Could not submit an auto-crafting job for {}: {}", describe(entry.stack()),
                        describeError(result));
                pending.set(i, entry.withCooldown(FAILURE_COOLDOWN_TICKS));
            }
        }

        return submitted;
    }

    /**
     * Submits a finished plan on the request's own grid, with the request's requester so the output is delivered into
     * the ME network instead of being discarded.
     */
    @Nullable
    private static ICraftingSubmitResult submit(ICraftingPlan plan, Entry entry) {
        ICraftingSubmitResult result;
        try {
            result = entry.requester().grid.getCraftingService()
                    .submitJob(plan, entry.requester(), null, false, entry.requester().source());
        } catch (RuntimeException e) {
            LOGGER.debug("Submitting an auto-crafting job failed", e);
            return null;
        }

        if (result != null && result.successful()) {
            entry.requester().track(result.link());
        }
        return result;
    }

    @Nullable
    private static ICraftingPlan resolve(Future<ICraftingPlan> plan) {
        try {
            return plan.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | RuntimeException e) {
            LOGGER.debug("A crafting calculation failed", e);
            return null;
        }
    }

    @Nullable
    public static IGrid gridOf(@Nullable InterfaceBlockEntity iface) {
        if (iface == null) {
            return null;
        }

        InterfaceLogic logic = iface.getInterfaceLogic();
        IGridNode node = logic == null ? null : logic.getActionableNode();
        return node == null ? null : node.getGrid();
    }

    /**
     * @return an action host on {@code grid} to report crafting jobs under, or {@code null} if none is available.
     */
    @Nullable
    private static IActionHost actionHostOf(IGrid grid, List<InterfaceBlockEntity> interfaces) {
        for (InterfaceBlockEntity iface : interfaces) {
            IGridNode node = iface == null ? null : nodeOf(iface);
            if (node != null && node.getGrid() == grid && node.getOwner() instanceof IActionHost host) {
                return host;
            }
        }
        return null;
    }

    @Nullable
    private static IGridNode nodeOf(InterfaceBlockEntity iface) {
        InterfaceLogic logic = iface.getInterfaceLogic();
        return logic == null ? null : logic.getActionableNode();
    }

    /**
     * @return whether {@code grid} is reachable through an attached ME Interface that has a Crafting Card installed.
     */
    private static boolean hasCraftingCardOn(IGrid grid, List<InterfaceBlockEntity> interfaces) {
        for (InterfaceBlockEntity iface : interfaces) {
            if (iface != null && hasCraftingCard(iface) && gridOf(iface) == grid) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasCraftingCard(InterfaceBlockEntity iface) {
        InterfaceLogic logic = iface.getInterfaceLogic();
        return logic != null && logic.getInstalledUpgrades(AEItems.CRAFTING_CARD.asItem()) > 0;
    }

    /**
     * Converts a key back to an item stack.
     */
    @Nullable
    private static ItemStack asItemStack(AEKey what) {
        if (what instanceof AEItemKey itemKey) {
            ItemStack stack = itemKey.toStack();
            if (!stack.isEmpty()) {
                return stack;
            }
        }
        return null;
    }

    public static String describe(@Nullable GenericStack stack) {
        if (stack == null) {
            return "nothing";
        }

        ItemStack itemStack = asItemStack(stack.what());
        return itemStack == null ? String.valueOf(stack.what()) : itemStack.getHoverName().getString();
    }

    /**
     * Renders a submission failure for the log.
     */
    public static String describeError(@Nullable ICraftingSubmitResult result) {
        if (result == null || result.errorCode() == null) {
            return "no suitable crafting CPU";
        }

        Object detail = result.errorDetail();
        String code = result.errorCode().name().toLowerCase(Locale.ROOT);
        return detail instanceof GenericStack missing ? code + " (" + describe(missing) + ")" : code;
    }
}
