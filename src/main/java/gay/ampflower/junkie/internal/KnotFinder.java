package gay.ampflower.junkie.internal;// Created 2023-23-01T00:02:19

import net.gudenau.minecraft.asm.impl.ReflectionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * @author Ampflower
 * @since 0.3.2
 **/
public class KnotFinder {
    private static final Logger logger = LogManager.getLogger();

    private static Set<ClassLoader> loadersSeen = new HashSet<>();
    private static Queue<ClassLoader> loaders = new ArrayDeque<>();

    private static ClassLoader knot;
    private static Object delegate;
    private static Field transformer;

    private static final MethodSignature transformClassBytes = new MethodSignature("transformClassBytes", byte[].class, String.class, String.class, byte[].class);

    public static ClassLoader getKnot() {
        if (knot != null) {
            return knot;
        }

        ClassLoader loader = KnotFinder.class.getClassLoader();

        loadersSeen.add(loader);
        loaders.add(loader);

        while ((loader = loaders.poll()) != null) {
            loader = seek(loader);
            if (loader != null) {
                // Clear these to avoid memleaking, as we never need these again.
                loadersSeen = null;
                loaders = null;

                // Return Knot.
                return loader;
            }
        }

        throw new AssertionError("Knot was not found in loader tree.");
    }

    private static ClassLoader seek(ClassLoader loader) {
        for (ClassLoader system = ClassLoader.getSystemClassLoader(); loader != null && loader != system; loader = loader.getParent())
            try {
                loadersSeen.add(loader);
                logger.info("Traversing {}", loader);

                final Class<? extends ClassLoader> clazz = loader.getClass();

                Field field = tryDelegateImpl(clazz);

                if (field == null) {
                    field = tryDelegate(loader, clazz);
                }

                if (field != null) {
                    transformer = field;
                    return knot = loader;
                }

                findClassLoaders(loader);
            } catch (Throwable throwable) {
                throw new AssertionError("This should never throw", throwable);
            }

        return null;
    }

    public static Object getTransformer() {
        if (knot == null) {
            getKnot();
        }

        try {
            return get(delegate, transformer);
        } catch (Throwable throwable) {
            throw new AssertionError("This should never throw", throwable);
        }
    }

    public static void setTransformer(Object value) {
        if (knot == null) {
            getKnot();
        }

        try {
            set(delegate, transformer, value);
        } catch (Throwable throwable) {
            throw new AssertionError("This should never throw", throwable);
        }
    }

    private static void findClassLoaders(final ClassLoader loader) {
        for (final Field field : loader.getClass().getDeclaredFields()) {
            if (ClassLoader.class.isAssignableFrom(field.getType())) {
                ClassLoader sub = get(loader, field);
                if (loadersSeen.add(sub)) {
                    loaders.add(sub);
                }
            }
        }
    }

    private static Field tryDelegate(final Object instance, final Class<?> loader) throws Throwable {
        final Set<Class<?>> witness = new HashSet<>();
        {
            final Field field = ReflectionHelper.findField(loader, "delegate");

            if (field != null) {
                witness.add(field.getType());

                final Object delegateInst = get(instance, field);

                if (delegateInst != null) {
                    witness.add(delegateInst.getClass());
                    Field transformer = tryDelegateImpl(field.getType());

                    if (transformer == null) {
                        transformer = tryDelegateImpl(delegateInst.getClass());
                    }

                    if (transformer != null) {
                        delegate = delegateInst;
                        return transformer;
                    }
                }
            }
        }

        for (final Field declaredField : loader.getDeclaredFields()) {
            final Class<?> type = declaredField.getType();

            if (!mayScan(loader, type)) {
                continue;
            }


            final Object delegateInst = get(instance, declaredField);

            if (delegateInst != null) {

                Field transformer = null;

                if (witness.add(declaredField.getType())) {
                    transformer = tryDelegateImpl(declaredField.getType());
                }

                if (witness.add(delegateInst.getClass())) {
                    transformer = tryDelegateImpl(delegateInst.getClass());
                }

                if (transformer != null) {
                    delegate = delegateInst;
                    return transformer;
                }
            }
        }

        return null;
    }

    private static Field tryDelegateImpl(final Class<?> clazz) {
        Field field = ReflectionHelper.findField(clazz, "mixinTransformer");

        if (field == null) {
            field = findFieldOfType(clazz, IMixinTransformer.class);
        }

        if (field == null) {
            field = findFieldByMethod(clazz, transformClassBytes);
        }

        return field;
    }

    private static Field findFieldOfType(final Class<?> delegate, final Class<?> type) {
        for (final Field field : delegate.getDeclaredFields()) {
            if (type.isAssignableFrom(field.getType())) {
                return field;
            }
        }

        return null;
    }

    private static Field findFieldByMethod(final Class<?> delegate, final MethodSignature signature) {
        Set<Class<?>> witness = new HashSet<>();
        for (final Field field : delegate.getDeclaredFields()) {
            final Class<?> type = field.getType();
            if (witness.add(type) && methodsMatchesSignature(type, signature)) {
                return field;
            }
        }

        return null;
    }

    private static boolean methodsMatchesSignature(final Class<?> transformer, final MethodSignature signature) {
        for (final Method method : transformer.getDeclaredMethods()) {
            if (signature.matches(method)) {
                return true;
            }
        }

        return false;
    }

    // Avoid recursively looking at foreign-to-Knot classes.
    private static boolean mayScan(final Class<?> reference, final Class<?> potentialDelegate) {
        return reference.getClassLoader() == potentialDelegate.getClassLoader();
    }

    private static <T> T get(Object instance, Field field) {
        try {
            if (Modifier.isStatic(field.getModifiers())) {
                return (T) ReflectionHelper.getGetter(field).invoke();
            }
            return (T) ReflectionHelper.getGetter(instance, field).invoke();
        } catch (Throwable throwable) {
            throw new AssertionError("This should never fail", throwable);
        }
    }

    private static void set(Object instance, Field field, Object value) {
        try {
            if (Modifier.isStatic(field.getModifiers())) {
                ReflectionHelper.getSetter(field).invoke(value);
            }
            ReflectionHelper.getSetter(instance, field).invoke(value);
        } catch (Throwable throwable) {
            throw new AssertionError("This should never fail", throwable);
        }
    }

    private static class MethodSignature {
        private String name;
        private Class<?> rtype;
        private Class<?>[] param;

        private MethodSignature(String name, Class<?> rtype, Class<?>... param) {
            this.name = name;
            this.rtype = rtype;
            this.param = param;
        }

        private boolean matches(Method method) {
            return method.getName().equals(name)
                    && method.getReturnType().equals(rtype)
                    && Arrays.equals(method.getParameterTypes(), param);
        }
    }
}
