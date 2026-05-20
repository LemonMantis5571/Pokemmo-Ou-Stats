package io.pokemmo.hook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

public final class PokemonStatsAgent {
    private PokemonStatsAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        logLine("agent", "{\"event\":\"agent_start\",\"ts\":\"" + Instant.now() + "\"}");
        AgentBuilder.Listener listener = new AgentBuilder.Listener.Adapter() {
            @Override
            public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
                logLine("agent", "{\"event\":\"transform_error\",\"type\":\"" + escape(typeName) + "\",\"message\":\"" + escape(String.valueOf(throwable)) + "\"}");
            }
        };

        new AgentBuilder.Default()
            .with(listener)
            .type(ElementMatchers.named("f.nu"))
            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                builder.visit(Advice.to(NuAdvice.class).on(ElementMatchers.named("X91")))
            )
            .asTerminalTransformation()
            .type(ElementMatchers.named("f.c21"))
            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                builder.visit(Advice.to(C21Advice.class).on(ElementMatchers.named("Au0")))
            )
            .installOn(inst);
    }

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

    private static String getLogPath(String channel) {
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
}
