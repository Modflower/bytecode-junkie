package gay.ampflower.junkie.internal.definer;// Created 2022-26-07T16:00:31

import net.gudenau.minecraft.asm.impl.ReflectionHelper;

import java.lang.invoke.MethodHandle;

/**
 * @author Ampflower
 * @since 0.3.2
 **/
public class Jdk9ClassDefiner implements ClassDefiner {
    private static MethodHandle handle;

    @Override
    public void tryLoad() throws ReflectiveOperationException {
        if (handle != null) {
            throw new IllegalStateException();
        }
        handle = ReflectionHelper.unsafeDefine0();
    }

    @Override
    public Class<?> define(ClassLoader loader, byte[] bytecode) throws Throwable {
        Class<?> type = (Class<?>) handle.invoke(bytecode);
        ReflectionHelper.ensureClassInitialised(type);
        return type;
    }

    @Override
    public boolean mangles() {
        return true;
    }

    @Override
    public int priority() {
        return 10;
    }
}
