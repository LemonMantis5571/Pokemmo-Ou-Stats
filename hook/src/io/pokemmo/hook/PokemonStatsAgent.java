package io.pokemmo.hook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

public final class PokemonStatsAgent {
    private PokemonStatsAgent() {
    }

    // --- Auto-dump state ---
    private static Instrumentation savedInstrumentation;
    private static volatile boolean autoDumpStarted = false;
    private static final Set<Short> dumpedSpecies = ConcurrentHashMap.newKeySet();
    private static volatile String dynamicNuLogPath = null;

    // c21 click-handler capture (fallback sender strategy)
    private static volatile boolean cg1Captured = false;
    private static volatile Object capturedC21;
    private static volatile Object[] capturedCG1Args;

    public static void premain(String agentArgs, Instrumentation inst) {
        savedInstrumentation = inst;
        logLine("agent", "{\"event\":\"agent_start\",\"ts\":\"" + Instant.now() + "\"}");
        AgentBuilder.Listener listener = new AgentBuilder.Listener.Adapter() {
            @Override
            public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
                logLine("agent", "{\"event\":\"transform_error\",\"type\":\"" + escape(typeName) + "\",\"message\":\"" + escape(String.valueOf(throwable)) + "\"}");
            }
        };

        new AgentBuilder.Default()
            .with(listener)
            // Hook inbound stats packet
            .type(ElementMatchers.named("f.nu"))
            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                builder.visit(Advice.to(NuAdvice.class).on(ElementMatchers.named("X91")))
            )
            .asTerminalTransformation()
            // Hook f.c21 for both rendering (Au0) and click handler (cG1)
            .type(ElementMatchers.named("f.c21"))
            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                builder.visit(Advice.to(C21Advice.class).on(ElementMatchers.named("Au0")))
                       .visit(Advice.to(CG1Advice.class).on(ElementMatchers.named("cG1")))
            )
            .asTerminalTransformation()
            // Hook outbound stats request packet constructor
            .type(ElementMatchers.named("f.Te0"))
            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                builder.visit(Advice.to(Te0Advice.class).on(ElementMatchers.isConstructor()))
            )
            .installOn(inst);
    }

    // =========================================================================
    // Existing packet / render logging (unchanged)
    // =========================================================================

    public static void logNu(Object self) {
        try {
            int overallUsageCount = getNumber(self, "cQ1").intValue();
            int wins = getNumber(self, "RV").intValue();
            int tournamentMatches = getNumber(self, "K01").intValue();
            int tournamentUsageCount = getNumber(self, "Ax1").intValue();
            int overallMatches = getNumber(self, "pF0").intValue();
            int matchmakingMatches = getNumber(self, "lv1").intValue();
            int matchmakingUsageCount = getNumber(self, "ru0").intValue();
            int speciesId = getNumber(self, "tM0").intValue();
            dumpedSpecies.add((short) speciesId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "nu_packet");
            payload.put("ts", Instant.now().toString());
            payload.put("speciesId", speciesId);
            payload.put("speciesName", resolveSpeciesName(speciesId));
            payload.put("overallMatches", overallMatches);
            payload.put("overallUsageCount", overallUsageCount);
            payload.put("wins", wins);
            payload.put("tournamentMatches", tournamentMatches);
            payload.put("tournamentUsageCount", tournamentUsageCount);
            payload.put("matchmakingMatches", matchmakingMatches);
            payload.put("matchmakingUsageCount", matchmakingUsageCount);
            payload.put("topItems", getEntries(self, "jC1", "item", wins));
            payload.put("topNatures", getEntries(self, "VQ0", "nature", wins));
            payload.put("topAbilities", getEntries(self, "nT1", "ability", wins));
            payload.put("commonAllies", getEntries(self, "xs1", "species", wins));
            logLine("nu", toJson(payload));
        } catch (Throwable t) {
            logLine("agent", "{\"event\":\"log_error\",\"message\":\"" + escape(String.valueOf(t)) + "\"}");
        }
    }

    public static void logC21Render(
        int overallMatches,
        int overallUsageCount,
        int wins,
        int tournamentMatches,
        int tournamentUsageCount,
        int matchmakingMatches,
        int matchmakingUsageCount,
        short speciesId
    ) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "c21_render");
            payload.put("ts", Instant.now().toString());
            payload.put("speciesId", (int) speciesId);
            payload.put("overallMatches", overallMatches);
            payload.put("overallUsageCount", overallUsageCount);
            payload.put("wins", wins);
            payload.put("tournamentMatches", tournamentMatches);
            payload.put("tournamentUsageCount", tournamentUsageCount);
            payload.put("matchmakingMatches", matchmakingMatches);
            payload.put("matchmakingUsageCount", matchmakingUsageCount);
            logLine("nu", toJson(payload));
        } catch (Throwable t) {
            logLine("agent", "{\"event\":\"render_log_error\",\"message\":\"" + escape(String.valueOf(t)) + "\"}");
        }
    }

    // =========================================================================
    // Auto-dump: c21.cG1 capture (click handler)
    // =========================================================================

    /**
     * Called when c21.cG1 (the pokemon click handler) is entered.
     * Captures the c21 instance and the method arguments so we can replay
     * it later for every species.
     */
    public static void onCG1Called(Object self, Object[] args) {
        if (cg1Captured) return; // capture once
        cg1Captured = true;
        capturedC21 = self;
        capturedCG1Args = args.clone();

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("event", "cg1_captured");
        info.put("argCount", args.length);
        for (int i = 0; i < args.length; i++) {
            info.put("arg" + i, args[i] != null ? args[i].toString() : "null");
            info.put("arg" + i + "_type", args[i] != null
                ? args[i].getClass().getSimpleName() : "null");
        }
        logLine("agent", toJson(info));
    }

    // =========================================================================
    // Auto-dump: Te0 interception — triggers the dump pipeline
    // =========================================================================

    public static void onTe0Created(Object self, Object[] constructorArgs) {
        if (autoDumpStarted) return;
        autoDumpStarted = true;

        try {
            // Determine dynamic log path from requested month
            if (constructorArgs.length > 0 && constructorArgs[0] instanceof Number) {
                int monthVal = ((Number) constructorArgs[0]).intValue();
                if (monthVal >= 1 && monthVal <= 12) {
                    int currentMonth = java.time.LocalDate.now().getMonthValue();
                    int currentYear = java.time.LocalDate.now().getYear();
                    int year = currentYear;
                    if (monthVal > currentMonth) {
                        year = currentYear - 1;
                    }
                    String[] MONTH_NAMES = {
                        "", "january", "february", "march", "april", "may", "june",
                        "july", "august", "september", "october", "november", "december"
                    };
                    String monthName = MONTH_NAMES[monthVal];
                    String custom = System.getProperty("pokemmo.hook.log");
                    if (custom != null && !custom.isBlank()) {
                        File originalFile = new File(custom);
                        File parentDir = originalFile.getParentFile();
                        if (parentDir != null) {
                            dynamicNuLogPath = new File(parentDir, "pvp-stats-" + monthName + "-" + year + ".jsonl").getAbsolutePath();
                        } else {
                            dynamicNuLogPath = "pvp-stats-" + monthName + "-" + year + ".jsonl";
                        }
                    } else {
                        dynamicNuLogPath = System.getProperty("user.home") + File.separator + "pvp-stats-" + monthName + "-" + year + ".jsonl";
                    }
                    logLine("agent", "{\"event\":\"dynamic_log_path\",\"path\":\"" + escape(dynamicNuLogPath) + "\"}");
                }
            }

            Class<?> te0Class = self.getClass();

            // --- Discovery: log Te0 class structure ---
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("event", "te0_discovery");
            info.put("ts", Instant.now().toString());
            info.put("class", te0Class.getName());
            info.put("super", te0Class.getSuperclass() != null
                ? te0Class.getSuperclass().getName() : "null");
            info.put("argCount", constructorArgs.length);
            for (int i = 0; i < constructorArgs.length; i++) {
                info.put("arg" + i, constructorArgs[i]);
                info.put("arg" + i + "_type", constructorArgs[i] != null
                    ? constructorArgs[i].getClass().getSimpleName() : "null");
            }
            logLine("agent", toJson(info));

            // Log Te0 instance fields
            for (Field f : te0Class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    logLine("agent", "{\"event\":\"te0_field\",\"name\":\""
                        + f.getName() + "\",\"type\":\"" + f.getType().getName()
                        + "\",\"value\":\"" + escape(String.valueOf(f.get(self)))
                        + "\"}");
                } catch (Throwable ignored) {}
            }

            // Log stack trace
            StringBuilder sb = new StringBuilder();
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                sb.append(e.toString()).append('\n');
            }
            logLine("agent", "{\"event\":\"te0_stacktrace\",\"trace\":\""
                + escape(sb.toString()) + "\"}");

            // --- Identify speciesId in Te0 constructor args ---
            int speciesArgIdx = findSpeciesArgIndex(constructorArgs,
                System.getProperty("pokemmo.hook.species_arg_index"));
            logLine("agent", "{\"event\":\"te0_species_arg\",\"index\":" + speciesArgIdx + "}");

            if (speciesArgIdx >= 0) {
                dumpedSpecies.add(((Number) constructorArgs[speciesArgIdx]).shortValue());
            }

            // --- Identify speciesId in cG1 args (for replay fallback) ---
            int cg1SpeciesIdx = -1;
            if (capturedCG1Args != null) {
                cg1SpeciesIdx = findSpeciesArgIndex(capturedCG1Args, null);
                logLine("agent", "{\"event\":\"cg1_species_arg\",\"index\":" + cg1SpeciesIdx + "}");
            }

            // Fire background auto-dump
            startAutoDumpThread(te0Class, constructorArgs.clone(), speciesArgIdx, cg1SpeciesIdx);
        } catch (Throwable t) {
            logLine("agent", "{\"event\":\"te0_error\",\"message\":\""
                + escape(String.valueOf(t)) + "\"}");
            autoDumpStarted = false;
        }
    }

    /**
     * Finds which argument index holds the speciesId (a number in range 1-999).
     * Prefers Short type, then Integer.
     */
    private static int findSpeciesArgIndex(Object[] args, String overrideProp) {
        if (overrideProp != null) {
            try { return Integer.parseInt(overrideProp); }
            catch (NumberFormatException ignored) {}
        }
        // Prefer Short
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Short) {
                short v = (Short) args[i];
                if (v > 0 && v < 1000) return i;
            }
        }
        // Then Integer
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Integer) {
                int v = (Integer) args[i];
                if (v > 0 && v < 1000) return i;
            }
        }
        return -1;
    }

    // -------------------------------------------------------------------------
    // Background auto-dump thread
    // -------------------------------------------------------------------------

    private static void startAutoDumpThread(
        Class<?> te0Class, Object[] te0TemplateArgs, int te0SpeciesIdx,
        int cg1SpeciesIdx
    ) {
        Thread thread = new Thread(() -> {
            try {
                long delay = Long.parseLong(
                    System.getProperty("pokemmo.hook.dump.delay", "300"));
                logLine("agent", "{\"event\":\"autodump_start\",\"delay\":" + delay + "}");
                System.out.println("\n[PokeMMO Stats Hook] Starting background auto-dump (delay: " + delay + "ms)...");

                // Give time for the first response + c21 capture
                Thread.sleep(3000);

                // --- Strategy 1: find a real packet sender via class scanning ---
                Object[] sender = findSender(te0Class);

                if (sender != null) {
                    logLine("agent", "{\"event\":\"autodump_strategy\",\"strategy\":\"direct_sender\"}");
                    autoDumpViaSender(
                        te0Class, te0TemplateArgs, te0SpeciesIdx,
                        sender[0], (Method) sender[1], delay);
                    return;
                }

                // --- Strategy 2: replay c21.cG1 (the click handler) ---
                if (capturedCG1Args != null && cg1SpeciesIdx >= 0) {
                    try {
                        Class<?> c21Class = Class.forName("f.c21");
                        Method cg1Method = findCG1Method(c21Class);
                        if (cg1Method != null) {
                            boolean isStatic = Modifier.isStatic(cg1Method.getModifiers());
                            if (isStatic || capturedC21 != null) {
                                logLine("agent", "{\"event\":\"autodump_strategy\",\"strategy\":\"c21_cg1_replay\",\"static\":" + isStatic + "}");
                                autoDumpViaCG1(cg1Method, cg1SpeciesIdx, delay);
                                return;
                            }
                        }
                    } catch (Throwable e) {
                        logLine("agent", "{\"event\":\"cg1_find_error\",\"message\":\"" + escape(e.toString()) + "\"}");
                    }
                }

                logLine("agent", "{\"event\":\"autodump_error\",\"message\":"
                    + "\"No sending strategy available. capturedC21="
                    + (capturedC21 != null) + " cg1Args="
                    + (capturedCG1Args != null) + " cg1SpeciesIdx="
                    + cg1SpeciesIdx + "\"}");

            } catch (Throwable t) {
                logLine("agent", "{\"event\":\"autodump_thread_error\",\"message\":\""
                    + escape(t.toString()) + "\"}");
            } finally {
                autoDumpStarted = false;
                dumpedSpecies.clear();
                System.out.println("[PokeMMO Stats Hook] Background auto-dump thread finished.");
            }
        }, "pokemmo-auto-dump");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Strategy 1: create Te0 packets directly and send via discovered sender.
     */
    private static void autoDumpViaSender(
        Class<?> te0Class, Object[] templateArgs, int speciesArgIdx,
        Object senderInstance, Method sendMethod, long delay
    ) throws Exception {
        Constructor<?> constructor = null;
        for (Constructor<?> c : te0Class.getDeclaredConstructors()) {
            if (c.getParameterCount() == templateArgs.length) {
                constructor = c; break;
            }
        }
        if (constructor == null) {
            logLine("agent", "{\"event\":\"autodump_error\",\"message\":\"No matching Te0 constructor\"}");
            return;
        }
        constructor.setAccessible(true);

        List<Short> species = getSpeciesList();
        logLine("agent", "{\"event\":\"autodump_species_count\",\"count\":" + species.size() + "}");
        int sent = 0, skipped = 0, total = species.size();
        System.out.println("[PokeMMO Stats Hook] Direct Sender Strategy: starting dump of " + total + " species...");

        for (Short speciesId : species) {
            if (dumpedSpecies.contains(speciesId)) { skipped++; continue; }
            try {
                Object[] newArgs = templateArgs.clone();
                newArgs[speciesArgIdx] = castArg(templateArgs[speciesArgIdx], speciesId);
                Object packet = constructor.newInstance(newArgs);
                sendMethod.invoke(senderInstance, packet);
                dumpedSpecies.add(speciesId);
                sent++;
                if (sent % 20 == 0) logProgress(sent, skipped, total);
                Thread.sleep(delay);
            } catch (Throwable ex) {
                logLine("agent", "{\"event\":\"autodump_send_error\",\"species\":"
                    + speciesId + ",\"message\":\"" + escape(ex.toString()) + "\"}");
            }
        }
        logLine("agent", "{\"event\":\"autodump_complete\",\"sent\":" + sent
            + ",\"skipped\":" + skipped + ",\"total\":" + total + "}");
        System.out.println("[PokeMMO Stats Hook] Auto-dump COMPLETED! Sent: " + sent + ", Skipped: " + skipped + ", Total: " + total);
    }

    /**
     * Strategy 2: replay c21.cG1 (the click handler) for each species.
     * This reuses the game's own send logic — no need to find the network layer.
     */
    private static void autoDumpViaCG1(
        Method cg1Method, int speciesArgIdx, long delay
    ) throws Exception {
        List<Short> species = getSpeciesList();
        logLine("agent", "{\"event\":\"autodump_species_count\",\"count\":" + species.size() + "}");
        int sent = 0, skipped = 0, total = species.size();
        System.out.println("[PokeMMO Stats Hook] CG1 Replay Strategy: starting dump of " + total + " species...");

        for (Short speciesId : species) {
            if (dumpedSpecies.contains(speciesId)) { skipped++; continue; }
            try {
                Object[] newArgs = capturedCG1Args.clone();
                newArgs[speciesArgIdx] = castArg(capturedCG1Args[speciesArgIdx], speciesId);
                cg1Method.invoke(capturedC21, newArgs);
                dumpedSpecies.add(speciesId);
                sent++;
                if (sent % 20 == 0) logProgress(sent, skipped, total);
                Thread.sleep(delay);
            } catch (Throwable ex) {
                logLine("agent", "{\"event\":\"autodump_send_error\",\"species\":"
                    + speciesId + ",\"message\":\"" + escape(ex.toString()) + "\"}");
            }
        }
        logLine("agent", "{\"event\":\"autodump_complete\",\"sent\":" + sent
            + ",\"skipped\":" + skipped + ",\"total\":" + total + "}");
        System.out.println("[PokeMMO Stats Hook] Auto-dump COMPLETED! Sent: " + sent + ", Skipped: " + skipped + ", Total: " + total);
    }

    private static void logProgress(int sent, int skipped, int total) {
        logLine("agent", "{\"event\":\"autodump_progress\",\"sent\":" + sent
            + ",\"skipped\":" + skipped + ",\"total\":" + total + "}");
        System.out.println("[PokeMMO Stats Hook] Auto-dump progress: requested " + (sent + skipped) + "/" + total + " species...");
    }

    /**
     * Find the cG1 method on c21 that matches the captured argument count.
     */
    private static Method findCG1Method(Class<?> c21Class) {
        for (Method m : c21Class.getDeclaredMethods()) {
            if (m.getName().equals("cG1")
                && capturedCG1Args != null
                && m.getParameterCount() == capturedCG1Args.length) {
                m.setAccessible(true);
                logLine("agent", "{\"event\":\"cg1_method_found\",\"params\":\""
                    + describeParams(m) + "\"}");
                return m;
            }
        }
        // Try any cG1 overload
        for (Method m : c21Class.getDeclaredMethods()) {
            if (m.getName().equals("cG1")) {
                m.setAccessible(true);
                logLine("agent", "{\"event\":\"cg1_method_fallback\",\"params\":\""
                    + describeParams(m) + "\"}");
                return m;
            }
        }
        logLine("agent", "{\"event\":\"cg1_method_not_found\"}");
        return null;
    }

    private static String describeParams(Method m) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> p : m.getParameterTypes()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p.getName());
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Sender discovery — scans loaded classes for a method that accepts the
    // packet base class (Te0's superclass) directly — NOT java.lang.Object.
    // -------------------------------------------------------------------------

    private static Object[] findSender(Class<?> te0Class) {
        Class<?> packetBase = te0Class.getSuperclass();
        if (packetBase == null || packetBase == Object.class) {
            logLine("agent", "{\"event\":\"sender_search\",\"message\":\"no useful superclass\"}");
            return null;
        }

        logLine("agent", "{\"event\":\"sender_search\",\"packetBase\":\""
            + packetBase.getName() + "\"}");

        // Also check interfaces on Te0 and its superclass
        List<Class<?>> acceptableParams = new ArrayList<>();
        acceptableParams.add(packetBase);
        for (Class<?> iface : te0Class.getInterfaces()) acceptableParams.add(iface);
        for (Class<?> iface : packetBase.getInterfaces()) acceptableParams.add(iface);
        // Walk superclass chain of packetBase (but stop before Object)
        Class<?> walk = packetBase.getSuperclass();
        while (walk != null && walk != Object.class) {
            acceptableParams.add(walk);
            walk = walk.getSuperclass();
        }

        logLine("agent", "{\"event\":\"sender_acceptable_params\",\"types\":\""
            + escape(acceptableParams.toString()) + "\"}");

        Class<?>[] loaded = savedInstrumentation.getAllLoadedClasses();

        for (Class<?> cls : loaded) {
            String name = cls.getName();
            // Skip obviously irrelevant classes
            if (name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("jdk.") || name.startsWith("sun.")
                || name.startsWith("net.bytebuddy.") || name.startsWith("io.pokemmo.hook."))
                continue;
            if (cls == te0Class) continue;

            try {
                for (Method m : cls.getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length != 1) continue;
                    // CRITICAL FIX: skip methods that accept Object (too broad)
                    if (params[0] == Object.class) continue;
                    // Skip known non-sender methods
                    String mn = m.getName();
                    if ("equals".equals(mn) || "hashCode".equals(mn)
                        || "toString".equals(mn) || "compareTo".equals(mn)
                        || "clone".equals(mn)) continue;

                    boolean match = acceptableParams.contains(params[0]);
                    if (!match) continue;

                    logLine("agent", "{\"event\":\"sender_candidate\",\"class\":\""
                        + cls.getName() + "\",\"method\":\"" + mn
                        + "\",\"param\":\"" + params[0].getName()
                        + "\",\"static\":" + Modifier.isStatic(m.getModifiers()) + "}");

                    Object instance;
                    if (Modifier.isStatic(m.getModifiers())) {
                        instance = null; // static methods don't need an instance
                    } else {
                        instance = findInstance(cls, loaded);
                        if (instance == null) continue;
                    }

                    m.setAccessible(true);
                    logLine("agent", "{\"event\":\"sender_found\",\"class\":\""
                        + cls.getName() + "\",\"method\":\"" + mn + "\"}");
                    return new Object[]{ instance, m };
                }
            } catch (Throwable ignored) {}
        }

        logLine("agent", "{\"event\":\"sender_search\",\"message\":\"no sender found\"}");
        return null;
    }

    private static Object findInstance(Class<?> cls, Class<?>[] allClasses) {
        // A — static self-referencing field
        for (Field f : cls.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType().isAssignableFrom(cls)) {
                try { f.setAccessible(true); Object v = f.get(null); if (v != null && cls.isInstance(v)) return v; }
                catch (Throwable ignored) {}
            }
        }
        // B — static no-arg factory
        for (Method m : cls.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())
                && m.getParameterCount() == 0
                && m.getReturnType().isAssignableFrom(cls)) {
                try { m.setAccessible(true); Object v = m.invoke(null); if (v != null && cls.isInstance(v)) return v; }
                catch (Throwable ignored) {}
            }
        }
        // C — static field on another class
        for (Class<?> other : allClasses) {
            String name = other.getName();
            if (name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("jdk.") || name.startsWith("sun.")
                || name.startsWith("net.bytebuddy.") || name.startsWith("io.pokemmo.hook."))
                continue;
            if (other == cls) continue;
            try {
                for (Field f : other.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        Class<?> ft = f.getType();
                        if (ft.isAssignableFrom(cls) || cls.isAssignableFrom(ft) || ft.isInterface()) {
                            f.setAccessible(true);
                            Object v = f.get(null);
                            if (v != null && cls.isInstance(v)) {
                                return v;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Species list helpers
    // -------------------------------------------------------------------------

    private static List<Short> getSpeciesList() {
        String prop = System.getProperty("pokemmo.hook.species");
        if (prop != null && !prop.isBlank()) {
            List<Short> list = new ArrayList<>();
            for (String tok : prop.split(",")) {
                try { list.add(Short.parseShort(tok.trim())); }
                catch (NumberFormatException ignored) {}
            }
            logLine("agent", "{\"event\":\"species_source\",\"source\":\"property\""
                + ",\"count\":" + list.size() + "}");
            return list;
        }
        try {
            Class<?> fq1Class = Class.forName("f.Fq1");
            Object fq1 = fq1Class.getMethod("NuL").invoke(null);
            Field mapField = fq1Class.getDeclaredField("aX1");
            mapField.setAccessible(true);
            Object map = mapField.get(fq1);
            @SuppressWarnings("unchecked")
            Set<?> keys = ((Map<?, ?>) map).keySet();
            List<Short> list = new ArrayList<>();
            for (Object key : keys) {
                if (key instanceof Number) list.add(((Number) key).shortValue());
            }
            Collections.sort(list);
            logLine("agent", "{\"event\":\"species_source\",\"source\":\"Fq1\""
                + ",\"count\":" + list.size() + "}");
            return list;
        } catch (Throwable t) {
            logLine("agent", "{\"event\":\"species_registry_error\",\"message\":\""
                + escape(t.toString()) + "\"}");
        }
        List<Short> list = new ArrayList<>();
        for (short i = 1; i <= 900; i++) list.add(i);
        logLine("agent", "{\"event\":\"species_source\",\"source\":\"fallback\""
            + ",\"count\":" + list.size() + "}");
        return list;
    }

    private static Object castArg(Object template, short newValue) {
        if (template instanceof Short)   return newValue;
        if (template instanceof Integer) return (int) newValue;
        if (template instanceof Byte)    return (byte) newValue;
        if (template instanceof Long)    return (long) newValue;
        return newValue;
    }

    // =========================================================================
    // Reflection / name-resolution helpers (unchanged)
    // =========================================================================

    private static Number getNumber(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Number) field.get(target);
    }

    private static Object[] getEntries(Object target, String fieldName, String kind, int denominator) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        Object[] entries = (Object[]) field.get(target);
        List<Object> out = new ArrayList<>(entries.length);
        for (int i = 0; i < entries.length; i++) {
            Object entry = entries[i];
            Class<?> cls = entry.getClass();
            Field idField = cls.getDeclaredField("pZ");
            Field countField = cls.getDeclaredField("gY0");
            idField.setAccessible(true);
            countField.setAccessible(true);
            int id = ((Number) idField.get(entry)).intValue();
            int count = ((Number) countField.get(entry)).intValue();
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("id", id);
            mapped.put("count", count);
            mapped.put("name", resolveEntryName(kind, id));
            if (denominator > 0) {
                mapped.put("percent", round2((count * 100.0) / denominator));
            }
            out.add(mapped);
        }
        return out.toArray();
    }

    private static String resolveEntryName(String kind, int id) {
        return switch (kind) {
            case "species" -> resolveSpeciesName(id);
            case "item" -> resolveItemName(id);
            case "nature" -> resolveNatureName(id);
            case "ability" -> resolveAbilityName(id);
            default -> "id:" + id;
        };
    }

    private static String resolveSpeciesName(int speciesId) {
        try {
            Class<?> fq1Class = Class.forName("f.Fq1");
            Object fq1 = fq1Class.getMethod("NuL").invoke(null);
            Field mapField = fq1Class.getDeclaredField("aX1");
            mapField.setAccessible(true);
            Object map = mapField.get(fq1);
            Object monster = map.getClass().getMethod("get", Object.class).invoke(map, Short.valueOf((short) speciesId));
            if (monster == null) {
                return "species:" + speciesId;
            }
            Method dz = monster.getClass().getMethod("DZ", boolean.class);
            return String.valueOf(dz.invoke(monster, Boolean.FALSE));
        } catch (Throwable t) {
            return "species:" + speciesId;
        }
    }

    private static String resolveAbilityName(int abilityId) {
        return resolveStringId(210000 + abilityId, "ability:" + abilityId);
    }

    private static String resolveNatureName(int natureId) {
        try {
            Class<?> ns0Class = Class.forName("f.ns0");
            Field of1 = ns0Class.getDeclaredField("of1");
            of1.setAccessible(true);
            Object registry = of1.get(null);
            Method t70 = registry.getClass().getMethod("t70", byte.class);
            Object nature = t70.invoke(registry, (byte) natureId);
            Field h71 = nature.getClass().getDeclaredField("h71");
            h71.setAccessible(true);
            int stringOffset = ((Number) h71.get(nature)).intValue();
            return resolveStringId(180000 + stringOffset, "nature:" + natureId);
        } catch (Throwable t) {
            return "nature:" + natureId;
        }
    }

    private static String resolveItemName(int itemId) {
        try {
            if (itemId == 5137) {
                return resolveStringId(1452, "Mail Item");
            }
            Class<?> yy0Class = Class.forName("f.YY0");
            Field mk1 = yy0Class.getDeclaredField("Mk1");
            mk1.setAccessible(true);
            Object itemRegistry = mk1.get(null);
            Method lpt9 = itemRegistry.getClass().getMethod("lPt9", short.class);
            Object item = lpt9.invoke(itemRegistry, (short) itemId);
            if (item == null) {
                return "item:" + itemId;
            }
            Field fb = item.getClass().getDeclaredField("fb");
            fb.setAccessible(true);
            int stringId = ((Number) fb.get(item)).intValue();
            return resolveStringId(stringId, "item:" + itemId);
        } catch (Throwable t) {
            return "item:" + itemId;
        }
    }

    private static String resolveStringId(int stringId, String fallback) {
        try {
            Class<?> nv0Class = Class.forName("f.nV0");
            Method id1 = nv0Class.getMethod("Id1", int.class);
            Object value = id1.invoke(null, stringId);
            if (value == null) {
                return fallback;
            }
            String text = String.valueOf(value);
            return text.isBlank() ? fallback : text;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // =========================================================================
    // Logging / serialisation (unchanged)
    // =========================================================================

    private static String getLogPath(String channel) {
        if (dynamicNuLogPath != null) {
            return dynamicNuLogPath;
        }
        String custom = System.getProperty("pokemmo.hook.log");
        if (custom != null && !custom.isBlank()) {
            return custom;
        }
        String base = System.getProperty("user.home") + File.separator + "pokemmo-hook-" + channel + ".jsonl";
        return base;
    }

    private static synchronized void logLine(String channel, String line) {
        File file = new File(getLogPath(channel));
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write('\n');
        } catch (IOException ignored) {
        }
    }

    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "\"" + escape(s) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(toJson(String.valueOf(entry.getKey())));
                sb.append(':');
                sb.append(toJson(entry.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
        if (value instanceof Object[] arr) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(toJson(arr[i]));
            }
            sb.append(']');
            return sb.toString();
        }
        return toJson(String.valueOf(value));
    }

    static String escape(String s) {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t");
    }

    // =========================================================================
    // ByteBuddy Advice classes
    // =========================================================================

    public static final class NuAdvice {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.This Object self) {
            PokemonStatsAgent.logNu(self);
        }
    }

    public static final class C21Advice {
        @Advice.OnMethodEnter
        public static void onEnter(
            @Advice.Argument(0) int overallMatches,
            @Advice.Argument(1) int overallUsageCount,
            @Advice.Argument(2) int wins,
            @Advice.Argument(3) int tournamentMatches,
            @Advice.Argument(4) int tournamentUsageCount,
            @Advice.Argument(5) int matchmakingMatches,
            @Advice.Argument(6) int matchmakingUsageCount,
            @Advice.Argument(7) short speciesId
        ) {
            PokemonStatsAgent.logC21Render(
                overallMatches,
                overallUsageCount,
                wins,
                tournamentMatches,
                tournamentUsageCount,
                matchmakingMatches,
                matchmakingUsageCount,
                speciesId
            );
        }
    }

    /**
     * Intercepts c21.cG1 — the click handler that creates and sends Te0.
     * Captures the c21 instance + method arguments for replay.
     */
    public static final class CG1Advice {
        @Advice.OnMethodEnter
        public static void onEnter(
            @Advice.This(optional = true) Object self,
            @Advice.AllArguments Object[] args
        ) {
            PokemonStatsAgent.onCG1Called(self, args);
        }
    }

    /**
     * Intercepts the Te0 constructor (outbound stats-request packet).
     * Fires once: captures args and kicks off the auto-dump pipeline.
     */
    public static final class Te0Advice {
        @Advice.OnMethodExit
        public static void onExit(
            @Advice.This Object self,
            @Advice.AllArguments Object[] args
        ) {
            PokemonStatsAgent.onTe0Created(self, args);
        }
    }
}
