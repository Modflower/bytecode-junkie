package net.gudenau.minecraft.asm.impl;

import net.gudenau.minecraft.asm.api.v1.*;

import java.util.*;
import java.util.stream.Collectors;

// Basic registry implementation
public class RegistryImpl implements AsmRegistry {
    public static final RegistryImpl INSTANCE = new RegistryImpl();

    private final List<Transformer> earlyTransformers = new LinkedList<>();
    private final List<RawTransformer> earlyRawTransformers = new LinkedList<>();
    private final List<RawTransformer> rawTransformers = new LinkedList<>();
    private final List<Transformer> transformers = new LinkedList<>();
    private final List<ClassCache> classCaches = new LinkedList<>();
    final Set<String> blacklist = new HashSet<>();

    private volatile Boolean frozen = null;

    private RegistryImpl() {
    }

    @Override
    public void registerEarlyTransformer(Transformer transformer) {
        if (frozen == null || frozen) {
            throw new RuntimeException("Attempted to register transformer outside initializer");
        }
        blacklist.add(transformer.getClass().getPackage().getName());
        earlyTransformers.add(transformer);
    }
    
    @Override
    public void registerTransformer(Transformer transformer){
        if(frozen == null || frozen){
            throw new RuntimeException("Attempted to register transformer outside initializer");
        }
        blacklist.add(transformer.getClass().getPackage().getName());
        transformers.add(transformer);
    }

    @Override
    public void registerEarlyRawTransformer(RawTransformer transformer) {
        if (frozen == null || frozen){
            throw new RuntimeException("Attempted to register transformer outside initializer");
        }
        blacklist.add(transformer.getClass().getPackage().getName());
        earlyRawTransformers.add(transformer);
    }

    @Override
    public void registerRawTransformer(RawTransformer transformer){
        if(frozen == null || frozen){
            throw new RuntimeException("Attempted to register transformer outside initializer");
        }
        blacklist.add(transformer.getClass().getPackage().getName());
        rawTransformers.add(transformer);
    }
    
    @Override
    public void registerClassCache(ClassCache cache){
        if(frozen == null || frozen){
            throw new RuntimeException("Attempted to register class cache outside initializer");
        }
        blacklist.add(cache.getClass().getPackage().getName());
        classCaches.add(cache);
    }
    
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private Optional<ClassCache> cache;
    
    @SuppressWarnings("OptionalAssignedToNull")
    public Optional<ClassCache> getCache(){
        if(cache == null){
            if(classCaches.isEmpty() || !Configuration.ENABLE_CACHE.get()){
                cache = Optional.empty();
            }else{
                String enabled = Configuration.ENABLED_CACHE.get();
                if(enabled != null){
                    Optional<ClassCache> existing = classCaches.stream()
                        .filter((c)->c.getName().toString().equals(enabled))
                        .findAny();
                    if(existing.isPresent()){
                        cache = existing;
                        return existing;
                    }
                }
                
                ClassCache newCache = classCaches.get(0);
                Configuration.ENABLED_CACHE.set(newCache.getName().toString());
                cache = Optional.of(newCache);
            }
        }
        return cache;
    }
    
    public List<String> getCacheNames(){
        return classCaches.stream().map(ClassCache::getName).map(Identifier::toString).collect(Collectors.toList());
    }

    public List<RawTransformer> getRawTransformers(){
        return rawTransformers;
    }

    public List<RawTransformer> getEarlyRawTransformers(){
        return earlyRawTransformers;
    }
    
    public List<Transformer> getTransformers(){
        return transformers;
    }
    
    public List<Transformer> getEarlyTransformers(){
        return earlyTransformers;
    }
    
    @SuppressWarnings("NonAtomicOperationOnVolatileField")
    public void setFrozen(boolean frozen){
        if(this.frozen == null){
            this.frozen = frozen;
        }else{
            this.frozen |= frozen;
        }
    }
}
