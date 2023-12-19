package net.gudenau.minecraft.asm.impl;

import net.fabricmc.loader.api.FabricLoader;
import net.gudenau.minecraft.asm.api.v1.AsmUtils;
import net.gudenau.minecraft.asm.api.v1.ClassCache;
import net.gudenau.minecraft.asm.api.v1.RawTransformer;
import net.gudenau.minecraft.asm.api.v1.Transformer;
import net.gudenau.minecraft.asm.util.Locker;
import gay.ampflower.junkie.internal.UnsafeProxy;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.transformer.FabricMixinTransformerProxy;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Our custom "mixin" transformer.
 */
class MixinTransformer extends FabricMixinTransformerProxy implements UnsafeProxy {
    private static final Type ANNOTATION_FORCE_BOOTLOADER = Type.getObjectType("net/gudenau/minecraft/asm/api/v1/annotation/ForceBootloader");

    private static final Set<String> BLACKLIST = new HashSet<>(Arrays.asList(
            "net.gudenau.minecraft.asm.",
            "org.objectweb.asm.",
            "com.google.gson.",
            "org.lwjgl.",
            "it.unimi.dsi.fastutil."
    ));

    private static final Transformer BOOTSTRAP_TRANSFORMER = new BootstrapTransformer();

    private final ClassLoader classLoader;

    private final Set<String> seenClasses = new HashSet<>();
    private final Locker seenClassesLocker = new Locker();

    private final IMixinTransformer parent;
    private final List<RawTransformer> rawTransformers;
    private final List<RawTransformer> earlyRawTransformers;
    private final List<Transformer> transformers;
    private final List<Transformer> earlyTransformers;

    private final boolean forceDump = Configuration.DUMP.get() == Configuration.DumpMode.FORCE;
    private final boolean dump = Configuration.DUMP.get() == Configuration.DumpMode.ON || forceDump;

    MixinTransformer(IMixinTransformer parent, ClassLoader classLoader) {
        this.parent = parent;
        this.classLoader = classLoader;
        earlyRawTransformers = RegistryImpl.INSTANCE.getEarlyRawTransformers();
        rawTransformers = RegistryImpl.INSTANCE.getRawTransformers();
        transformers = RegistryImpl.INSTANCE.getTransformers();
        earlyTransformers = RegistryImpl.INSTANCE.getEarlyTransformers();
    }

    public byte[] transformClassBytes(String name, String transformedName, byte[] basicClass) {
        if (seenClassesLocker.readLock(() -> seenClasses.contains(name))) {
            return basicClass;
        }
        if (seenClassesLocker.writeLock(() -> !seenClasses.add(name))) {
            return parent.transformClassBytes(name, transformedName, basicClass);
        }

        for(String prefix : BLACKLIST){
            if(name.startsWith(prefix)){
                byte[] transformedClass = parent.transformClassBytes(name, transformedName, basicClass);
                if(forceDump){
                    dump(name, basicClass);
                }
                return bootstrap(cache(basicClass, ()->transformedClass));
            }
        }
        return cache(basicClass, ()->{
            if(basicClass == null){
                return parent.transformClassBytes(name, transformedName, basicClass);
            }
            
            boolean shouldBootstrap = shouldBootstrap(basicClass);
            AtomicBoolean modified = new AtomicBoolean(forceDump);
            
            byte[] bytecode = basicClass;
            if(!earlyTransformers.isEmpty() || !earlyRawTransformers.isEmpty()){
                bytecode = transform(name, transformedName, bytecode, earlyRawTransformers, earlyTransformers, modified);
            }
            
            bytecode = parent.transformClassBytes(name, transformedName, bytecode);
    
            //FIXME, this is stupid
            List<Transformer> transformers = this.transformers;
            if(shouldBootstrap){
                List<Transformer> newList = new ArrayList<>(this.transformers);
                newList.add(BOOTSTRAP_TRANSFORMER);
                transformers = newList;
            }
            List<RawTransformer> rawTransformers = this.rawTransformers;
            if(shouldBootstrap){
                List<RawTransformer> newList = new ArrayList<>(this.rawTransformers);;
                rawTransformers = newList;
            }
            if(!transformers.isEmpty() || !rawTransformers.isEmpty()){
                bytecode = transform(name, transformedName, bytecode, rawTransformers, transformers, modified);
            }
            
            if(dump && modified.get()){
                dump(name, bytecode);
            }
            
            return bootstrap(bytecode, shouldBootstrap);
        });
    }
    
    private static final ExecutorService DUMP_SERVICE = Executors.newFixedThreadPool(1);
    static{
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            DUMP_SERVICE.shutdown();
            try{
                DUMP_SERVICE.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
            }catch(InterruptedException ignored){}
        }, "gudASM Dumper Service Cleanup"));
    }
    private void dump(String name, byte[] bytecode){
        DUMP_SERVICE.submit(()->{
            Path path = FabricLoader.getInstance().getGameDir().resolve("gudASMDump");
            String[] split = name.split("\\.");
            for(int i = 0; i < split.length - 1; i++){
                String s = split[i];
                path = path.resolve(s);
            }
            if(!Files.exists(path)){
                try{
                    Files.createDirectories(path);
                }catch(IOException ignored){}
            }
            path = path.resolve(split[split.length - 1] + ".class");
            try(OutputStream stream = Files.newOutputStream(path, StandardOpenOption.CREATE)){
                stream.write(bytecode);
            }catch(IOException ignored){}
        });
    }
    
    private byte[] transform(String name, String transformedName, byte[] bytecode, List<RawTransformer> rawTransformers, List<Transformer> transformers, AtomicBoolean parentModifier){
        List<RawTransformer> validRawTransformers = new ArrayList<>();
        for(RawTransformer transformer : rawTransformers){
            if(transformer.handlesClass(name, transformedName)){
                validRawTransformers.add(transformer);
            }
        }
        List<Transformer> validTransformers = new ArrayList<>();
        for(Transformer transformer : transformers){
            if(transformer.handlesClass(name, transformedName)){
                validTransformers.add(transformer);
            }
        }
        
        if(validTransformers.isEmpty() && rawTransformers.isEmpty()){
            return bytecode;
        }
        TransformerFlagsImpl flags = new TransformerFlagsImpl();

        for(RawTransformer transformer : validRawTransformers){
            bytecode = transformer.transform(bytecode, flags);
        }
        
        ClassNode classNode = new ClassNode();
        new ClassReader(bytecode).accept(classNode, 0);
        boolean modified = false;

        for(Transformer transformer : validTransformers){
            modified |= transformer.transform(classNode, flags);
        }
        if(!modified){
            return bytecode;
        }

        ClassWriter writer = Bootstrap.createClassWriter(flags.getClassWriterFlags(), classLoader);
        classNode.accept(writer);
        parentModifier.set(true);
        return writer.toByteArray();
    }
    
    byte[] cache(byte[] original, Supplier<byte[]> transformed){
        return transformed.get();
    }
    
    private boolean shouldBootstrap(byte[] bytecode){
        if(bytecode == null){
            return false;
        }
    
        ClassNode classNode = new ClassNode();
        new ClassReader(bytecode).accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return AsmUtils.hasAnnotation(classNode, ANNOTATION_FORCE_BOOTLOADER);
    }
    
    private byte[] bootstrap(byte[] bytecode){
        return bootstrap(bytecode, shouldBootstrap(bytecode));
    }
    
    private byte[] bootstrap(byte[] bytecode, boolean shouldBootstrap){
        if(bytecode == null){
            return null;
        }
        
        if(shouldBootstrap) {
            try {
                Bootstrap.forceDefineClass(null, bytecode, true);
            } catch (Throwable throwable) {
                new RuntimeException("Failed to force a class into the bootstrap ClassLoader", throwable).printStackTrace();
                System.exit(0);
            }

            return null;
        } else {
            return bytecode;
        }
    }

    public void blacklistPackages(Collection<String> packages) {
        BLACKLIST.addAll(packages);
    }

    public void blacklistPackage(String name) {
        BLACKLIST.add(name);
    }

    static class Cache extends MixinTransformer {
        private final ClassCache cache;

        Cache(IMixinTransformer parent, ClassLoader classLoader, ClassCache cache) {
            super(parent, classLoader);
            this.cache = cache;
        }

        @Override
        byte[] cache(byte[] original, Supplier<byte[]> transformer) {
            if (original == null) {
                return transformer.get();
            }

            Optional<byte[]> result = cache.getEntry(original);
            if (result.isPresent()) {
                return result.get();
            } else {
                byte[] transformed = transformer.get();
                if (transformed != null) {
                    cache.putEntry(original, transformed);
                }
                return transformed;
            }
        }
    }
}
