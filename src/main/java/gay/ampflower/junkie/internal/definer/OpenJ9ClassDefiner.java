package gay.ampflower.junkie.internal.definer;// Created 2022-26-07T15:57:21

import net.gudenau.minecraft.asm.impl.ReflectionHelper;

import java.lang.invoke.MethodHandle;

/**
 * @author Ampflower
 * @since 0.3.2
 **/
public class OpenJ9ClassDefiner implements ClassDefiner {
    private static MethodHandle handle;

    @Override
    public void tryLoad() throws ReflectiveOperationException {
        if (handle != null) {
            throw new IllegalStateException();
        }
        handle = ReflectionHelper.findVirtualDetached(
                ClassLoader.class,
                "defineClassImpl",
                Class.class,
                String.class, byte[].class, int.class, int.class, Object.class
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
                (Object) null
        );
        ReflectionHelper.ensureClassInitialised(type);
        return type;
    }

    @Override
    public boolean requiresLoader() {
        return true;
    }
}
