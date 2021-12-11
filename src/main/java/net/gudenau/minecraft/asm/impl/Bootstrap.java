package net.gudenau.minecraft.asm.impl;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.gudenau.minecraft.asm.api.v1.AsmInitializer;
import net.gudenau.minecraft.asm.api.v1.AsmUtils;
import net.gudenau.minecraft.asm.api.v1.ClassCache;
import net.gudenau.minecraft.asm.api.v1.type.MethodType;
import net.gudenau.minecraft.asm.util.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Objects;

// Bootstraps all the mess we make.
public class Bootstrap {
    private static final Logger log = LogManager.getLogger("gud_asm");

    static final MethodHandle ClassLoader$defineClass;

    static {
        try {
            ClassLoader$defineClass = ReflectionHelper.findStatic(
                    ClassLoader.class,
                    "defineClass1",
                    Class.class,
                    ClassLoader.class, String.class, byte[].class, int.class, int.class, ProtectionDomain.class, String.class
            );
        } catch (ReflectiveOperationException e) {
            new RuntimeException("Failed to get ClassLoader.defineClass1", e).printStackTrace();
            System.exit(0);
            // Unreachable, makes javac happy
            throw new RuntimeException("Failed to get ClassLoader.defineClass1", e);
        }
    }

    public static boolean enableCache = false;

    public static void setup() {
        // Load the configuration
        try {
            Configuration.load();
        } catch (IOException e) {
            e.printStackTrace();
        }

        FabricLoader loader = FabricLoader.getInstance();
        RegistryImpl registry = RegistryImpl.INSTANCE;
    
        registry.setFrozen(false);
        for(EntrypointContainer<AsmInitializer> container : loader.getEntrypointContainers("gud_asm", AsmInitializer.class)){
            AsmInitializer initializer = container.getEntrypoint();
            if(initializer != null){
                initializer.onInitializeAsm();
            }
        }
        registry.setFrozen(true);
        
        // Let the cache load itself
        ClassCache cache = registry.getCache().orElse(null);
        if(cache != null){
            try {
                cache.load();
                enableCache = true;
            } catch (IOException e) {
                new RuntimeException("Failed to load class cache " + cache.getName(), e).printStackTrace();
                cache = null;
            }
        }

        // Clean out the class dump if dumping is enabled
        if (Configuration.DUMP.get() != Configuration.DumpMode.OFF) {
            try {
                FileUtils.delete(loader.getGameDir().resolve("gudASMDump"));
            } catch (IOException ignored) {
            }
        }

        // Hack into knot.
        ClassLoader classLoader = Bootstrap.class.getClassLoader();

        try {
            unsafe$generic(classLoader, cache);
        } catch (Throwable t) {
            new RuntimeException("Failed to hook into Knot", t).printStackTrace();
            System.exit(0);
            // Unreachable
            throw new RuntimeException("Failed to hook into Knot", t);
        }
    }

    private static void unsafe$generic(ClassLoader classLoader, ClassCache cache) throws Throwable {
        Class<? extends ClassLoader> KnotClassLoader = classLoader.getClass();

        Class<?> KnotClassDelegate;
        Class<?> transformerClass;
        Object KnotClassLoader$delegate;

        {
            Field tmp = ReflectionHelper.findField(KnotClassLoader, "mixinTransformer");
            if (tmp != null) {
                KnotClassDelegate = KnotClassLoader;
                transformerClass = tmp.getType();
                KnotClassLoader$delegate = classLoader;
            } else {
                KnotClassDelegate = Objects.requireNonNull(ReflectionHelper.findField(KnotClassLoader, "delegate"), "NoSuchField: KnotClassLoader/delegate").getType();
                transformerClass = Objects.requireNonNull(ReflectionHelper.findField(KnotClassDelegate, "mixinTransformer"), "NoSuchField: KnotClassDelegate/mixinTransformer").getType();

                // Get the class delegate
                MethodHandle KnotClassLoader$delegate$getter = ReflectionHelper.findGetter(KnotClassLoader, classLoader, "delegate", KnotClassDelegate);
                KnotClassLoader$delegate = KnotClassLoader$delegate$getter.invoke();
            }
        }

        // Get the transformer proxy as Object
        MethodHandle KnotClassDelegate$mixinTransformer$getter = ReflectionHelper.findGetter(KnotClassDelegate, KnotClassLoader$delegate, "mixinTransformer", transformerClass);
        Object KnotClassDelegate$mixinTransformer = KnotClassDelegate$mixinTransformer$getter.invoke();

        // Get the environment's transformer.
        MethodHandle MixinEnvironment$transformer$getter = ReflectionHelper.findStaticGetter(MixinEnvironment.class, "transformer", IMixinTransformer.class);
        IMixinTransformer originalTransformer = (IMixinTransformer) MixinEnvironment$transformer$getter.invokeExact();

        // Clear it, otherwise it will kill Minecraft
        MethodHandle MixinEnvironment$transformer$setter = ReflectionHelper.findStaticSetter(MixinEnvironment.class, "transformer", IMixinTransformer.class);
        MixinEnvironment$transformer$setter.invokeExact((IMixinTransformer) null);

        // Create our transformer proxy.
        IMixinTransformer transformer;
        if (KnotClassDelegate$mixinTransformer instanceof IMixinTransformer) {
            // This can be run through the generic transformer route.
            transformer = loadUnsafeProxy(classLoader, (IMixinTransformer) KnotClassDelegate$mixinTransformer, null, cache);
        } else {
            // Why didn't it load under 0.7.4 logic? Perhaps we're dealing with Quilt instead?
            transformer = loadUnsafeProxy(classLoader, originalTransformer, Type.getType(KnotClassDelegate$mixinTransformer.getClass()), cache);
        }
        // MixinTransformer has in theory been transformed at this point, making the cast safe, even though it's
        // normally impossible.
        ((MixinTransformer) transformer).blacklistPackages(RegistryImpl.INSTANCE.blacklist);

        // Restore the original to keep the environment as sane as possible
        MixinEnvironment$transformer$setter.invokeExact(originalTransformer);

        // Set our custom transformer so it will be used in future class loads
        MethodHandle KnotClassDelegate$mixinTransformer$setter = ReflectionHelper.findSetter(KnotClassDelegate, KnotClassLoader$delegate, "mixinTransformer", transformerClass);
        KnotClassDelegate$mixinTransformer$setter.invoke(transformer);
    }

    /**
     * Constructs an {@link IMixinTransformer} proxy for use in the delegates.
     *
     * @param inject              The ClassLoader to load the newly constructed class into.
     * @param originalTransformer The transformer to proxy.
     * @param parent              The parent of the transformer, if needs to fit in a delegate.
     * @return An initialised ASM-based IMixinTransformer proxy.
     */
    private static IMixinTransformer loadUnsafeProxy(ClassLoader inject, IMixinTransformer originalTransformer, Type parent, ClassCache cache) throws Throwable {
        ClassNode unsafeTransformer = new ClassNode();

        // Open the proxy for modifying, then the transformer interface for reading methods.
        try (InputStream unsafeStream = Bootstrap.class.getResourceAsStream("MixinTransformer.class");
             InputStream transformerStream = IMixinTransformer.class.getResourceAsStream("IMixinTransformer.class")) {

            if (unsafeStream == null) {
                throw new ClassNotFoundException("Our MixinTransformer cannot be loaded?");
            }

            if (transformerStream == null) {
                throw new ClassNotFoundException("Mixin's IMixinTransformer cannot be loaded?");
            }

            ClassReader unsafeReader = new ClassReader(unsafeStream);
            ClassReader transformerReader = new ClassReader(transformerStream);
            HashSet<Method> methods = new HashSet<>();

            // Load the entire required structure into the final transformer.
            unsafeReader.accept(unsafeTransformer, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            for (MethodNode method : unsafeTransformer.methods) {
                methods.add(new Method(method.name, method.desc));
            }

            // Read the interface and make proxy methods as long as the methods are *not* static and private.
            // All other modifiers will be ignored, including final.
            transformerReader.accept(new ClassVisitor(Opcodes.ASM8) {
                // @Override
                // public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                //     // TODO: cyclic check on interfaces
                // }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0) {
                        // no-op, static methods don't matter.
                        return null;
                    }
                    if (!methods.add(new Method(name, descriptor))) {
                        log.warn("Unsafe Proxy: Method {}{} already exists.", name, descriptor);
                        return null;
                    }
                    // This is required for creating the proxy.
                    mkProxyMethod(unsafeTransformer, access & ~Opcodes.ACC_ABSTRACT, "parent",
                            Type.getType(IMixinTransformer.class), name, descriptor, signature, exceptions);
                    // This was only required for generating a redirecting stub.
                    return null;
                }

                @Override
                public void visitEnd() {
                    unsafeTransformer.visitEnd();
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES | ClassReader.SKIP_CODE);
            // The code isn't required as only the method signatures are required for forwarding.
        }

        MethodNode node = AsmUtils.findMethod(unsafeTransformer, "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(IMixinTransformer.class), Type.getType(ClassLoader.class))).orElseThrow(AssertionError::new);
        MethodInsnNode insn = AsmUtils.findNextMethodCall(node.instructions.getFirst(), AsmUtils.METHOD_FLAG_IGNORE_OWNER, Opcodes.INVOKESPECIAL, new MethodType(Type.VOID_TYPE, "<init>", Type.VOID_TYPE, new Type[0])).orElseThrow(AssertionError::new);
        insn.owner = unsafeTransformer.superName = parent != null ? parent.getInternalName() : "java/lang/Object";

        // All required interface methods now exist.
        unsafeTransformer.interfaces.add("org/spongepowered/asm/mixin/transformer/IMixinTransformer");

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        unsafeTransformer.accept(writer);

        byte[] bytecode = writer.toByteArray();

        Class<?> newTransformerProxy =
                forceDefineClass(inject, bytecode);

        // For some reason, Java 14 likes to load the MixinTransformer ahead of time if there's a new call for it anywhere
        // in the class. By leaving to indirection or casts, it prevents MixinTransformer from being loaded ahead of
        // time and allows the transformation to work. However, OpenJ9 may not be too happy about this, however.
        //noinspection ConstantConditions - This is valid *when transformed*
        return (IMixinTransformer) (cache != null ? new MixinTransformer.Cache(originalTransformer, inject, cache) : newTransformerProxy.getDeclaredConstructor(IMixinTransformer.class, ClassLoader.class).newInstance(originalTransformer, inject));
        //return (IMixinTransformer) (cache != null ? new MixinTransformer.Cache(originalTransformer, inject, cache) : new MixinTransformer(originalTransformer, inject));
        //return (IMixinTransformer) newTransformerProxy.getDeclaredConstructor(IMixinTransformer.class, ClassLoader.class).newInstance(originalTransformer, inject);
    }

    private static void mkProxyMethod(ClassNode classVisitor, int access, String proxiedField, Type proxied,
                                      String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor methodVisitor = classVisitor.visitMethod(access & ~Opcodes.ACC_ABSTRACT, name, descriptor,
                signature, exceptions);
        // load proxied
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, classVisitor.name, proxiedField, proxied.getDescriptor());

        // load arguments
        Type[] arguments = Type.getArgumentTypes(descriptor);
        for (int i = 0, l = arguments.length; i < l; i++) {
            methodVisitor.visitVarInsn(arguments[i].getOpcode(Opcodes.ILOAD), i + 1);
        }

        // invoke the method via INVOKEINTERFACE (safest default)
        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, proxied.getInternalName(), name, descriptor, true);

        // return output if applicable
        methodVisitor.visitInsn(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN));
        methodVisitor.visitEnd();
    }

    private static Class<?> forceDefineClass(ClassLoader loader, byte[] bytecode) throws Throwable {
        Class<?> type = (Class<?>) Bootstrap.ClassLoader$defineClass.invoke(
                loader,
                (String) null, // Let the JVM figure it out
                bytecode,
                0,
                bytecode.length,
                (ProtectionDomain) null,
                (String) null
        );
        // Forces initialization of the class.
        ReflectionHelper.ensureClassInitialised(type);
        return type;
    }
}
