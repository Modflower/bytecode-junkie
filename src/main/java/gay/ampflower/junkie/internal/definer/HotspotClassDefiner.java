package gay.ampflower.junkie.internal.definer;// Created 2022-26-07T15:53:04

import net.gudenau.minecraft.asm.impl.ReflectionHelper;

import java.lang.invoke.MethodHandle;
import java.security.ProtectionDomain;

/**
 * @author Ampflower
 * @since 0.3.2
 **/
public class HotspotClassDefiner implements ClassDefiner {
    private static MethodHandle handle;

    @Override
    public void tryLoad() throws ReflectiveOperationException {
        if (handle != null) {
            throw new IllegalStateException();
        }
        handle = ReflectionHelper.findStatic(
                ClassLoader.class,
                "defineClass1",
                Class.class,
                ClassLoader.class, String.class, byte[].class, int.class, int.class, ProtectionDomain.class, String.class
        );
    }

    @Override
    public Class<?> define(ClassLoader loader, byte[] bytecode) throws Throwable {
        Class<?> type = (Class<?>) handle.invoke(
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
