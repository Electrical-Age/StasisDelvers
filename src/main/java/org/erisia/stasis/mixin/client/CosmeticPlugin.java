package org.erisia.stasis.mixin.client;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The look-only client mixins (stasisdelvers.cosmetic.mixins.json: frozen textures, still rain, still particles, the
 * local sky clock, the suited hand, ambience and pitch). That config is optional with require=0 everywhere, so a mod
 * that rewrites one of these spots (a Sodium-like renderer, a weather or shader mod) costs only that effect instead
 * of stopping the game. Mixin is silent when a require=0 hook finds nothing, so this says so: one warning per mixin
 * when its target class is missing, or when one of its hooks found nothing to hook. Gameplay mixins are in the required
 * config and are unaffected.
 * <p>
 * (Loaded by Mixin before any mod or game class: only ASM, Mixin and SLF4J here.)
 */
public class CosmeticPlugin implements IMixinConfigPlugin {
    private static final Logger LOG = LoggerFactory.getLogger("stasisdelvers");
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** What each mixin does, for the warning. */
    private static final Map<String, String> EFFECT = Map.of(
        "SectionCompilerMixin", "still block textures in stopped land",
        "FluidRendererMixin", "still water/lava textures and water lines in stopped land",
        "WeatherEffectRendererMixin", "rain and snow hanging still in stopped land, and kept out by field shields",
        "ParticleGroupMixin", "particles holding still in stopped land",
        "ClientLevelMixin", "no block ambience, drips or rain sound in stopped land",
        "ClientClockManagerMixin", "the local sky time (frozen in stopped land, the field's in a field)",
        "ClientClockInstanceMixin", "the local sky time (frozen in stopped land, the field's in a field)",
        "GameRendererMixin", "the suited hand drawn in colour in stopped land",
        "SoundEngineMixin", "the pitch sag of a failing field",
        "EntityRendererAgeMixin", "frozen entities' idle animations standing still");

    /** Injector annotations (Mixin and MixinExtras) whose handler the target class must end up calling. */
    private static final Set<String> INJECTORS = Set.of(
        "Lorg/spongepowered/asm/mixin/injection/Inject;",
        "Lorg/spongepowered/asm/mixin/injection/Redirect;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
        "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
        "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
        "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
        "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
        "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
        "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;",
        "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");

    /**
     * Always applies: the config is required=false, so Mixin itself skips a target that isn't there. This only warns.
     * The quick check is a resource lookup on our own loader; that may not see game classes on every launcher, so a
     * miss is confirmed with Mixin's bytecode provider before warning, and any doubt (an exception) stays silent.
     * Refusing on a false miss would turn the effect off for nothing.
     */
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!present(targetClassName)) warn(mixinClassName, "its target class " + targetClassName + " isn't there");
        return true;
    }

    private boolean present(String className) {
        try {
            if (getClass().getClassLoader().getResource(className.replace('.', '/') + ".class") != null) return true;
        } catch (RuntimeException e) {
            LOG.debug("Stasis Delvers: resource lookup of {} failed", className, e);
        }
        try {
            MixinService.getService().getBytecodeProvider().getClassNode(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            LOG.debug("Stasis Delvers: couldn't look up {}", className, e);
            return true;
        }
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        try {
            List<String> missing = new ArrayList<>();
            for (MethodNode m : mixinInfo.getClassNode(0).methods) {
                AnnotationNode a = injector(m);
                if (a != null && !hooked(targetClass, m.name, a)) missing.add(m.name);
            }
            if (!missing.isEmpty())
                warn(mixinClassName, "hook(s) " + String.join(", ", missing) + " found nothing to hook in " + targetClassName
                    + " (another mod has probably changed that code)");
        } catch (RuntimeException e) {
            LOG.debug("Stasis Delvers: couldn't check {} in {}", mixinClassName, targetClassName, e);
        }
    }

    private static AnnotationNode injector(MethodNode m) {
        for (List<AnnotationNode> list : List.of(nn(m.visibleAnnotations), nn(m.invisibleAnnotations)))
            for (AnnotationNode a : list) if (INJECTORS.contains(a.desc)) return a;
        return null;
    }

    private static <T> List<T> nn(List<T> l) {
        return l == null ? List.of() : l;
    }

    /**
     * Did this handler find its spot? Mixin's own injectors (@Inject...) are woven by now, so the target must call the
     * handler (renamed by Mixin, keeping its name after a '$'). MixinExtras weaves its wrappers later, after this hook,
     * so for those: the call they wrap (@At INVOKE target) must still be in the target method as the other mixins left
     * it; a HEAD/RETURN-style point only needs the method.
     */
    private static boolean hooked(ClassNode target, String handler, AnnotationNode a) {
        if (a.desc.startsWith("Lorg/spongepowered/")) return called(target, handler);
        List<String> methods = new ArrayList<>();
        List<AnnotationNode> ats = new ArrayList<>();
        for (int i = 0; i + 1 < nn(a.values).size(); i += 2) {
            Object v = a.values.get(i + 1);
            switch ((String) a.values.get(i)) {
                case "method" -> { for (Object o : v instanceof List<?> l ? l : List.of(v)) methods.add((String) o); }
                case "at" -> { for (Object o : v instanceof List<?> l ? l : List.of(v)) ats.add((AnnotationNode) o); }
                default -> { }
            }
        }
        for (MethodNode m : target.methods) {
            if (!matches(m, methods)) continue;
            if (ats.isEmpty()) return true;
            for (AnnotationNode at : ats) {
                String kind = null, want = null;
                for (int i = 0; i + 1 < nn(at.values).size(); i += 2) {
                    if ("value".equals(at.values.get(i))) kind = (String) at.values.get(i + 1);
                    if ("target".equals(at.values.get(i))) want = (String) at.values.get(i + 1);
                }
                if (!"INVOKE".equals(kind) || want == null) return true;
                for (AbstractInsnNode insn : m.instructions)
                    if (insn instanceof MethodInsnNode c && want.equals("L" + c.owner + ";" + c.name + c.desc)) return true;
            }
        }
        return false;
    }

    /** Is m one of the annotation's methods ("name" or "name(desc)")? */
    private static boolean matches(MethodNode m, List<String> methods) {
        for (String s : methods) {
            int p = s.indexOf('(');
            if (p < 0 ? m.name.equals(s) : m.name.equals(s.substring(0, p)) && m.desc.equals(s.substring(p))) return true;
        }
        return false;
    }

    private static boolean called(ClassNode target, String handler) {
        String tail = "$" + handler;
        for (MethodNode m : target.methods)
            for (AbstractInsnNode insn : m.instructions) {
                if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(target.name)) continue;
                String n = call.name;
                if (n.equals(handler) || n.endsWith(tail)) return true;
            }
        return false;
    }

    private static void warn(String mixinClassName, String why) {
        if (!WARNED.add(mixinClassName)) return;
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        LOG.warn("Stasis Delvers: cosmetic mixin {} is not (fully) applied: {}. Off: {} (look only; gameplay is unaffected).",
            simple, why, EFFECT.getOrDefault(simple, "a visual effect"));
    }

    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
