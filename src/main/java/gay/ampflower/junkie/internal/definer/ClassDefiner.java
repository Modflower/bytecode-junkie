package gay.ampflower.junkie.internal.definer;// Created 2022-26-07T15:51:43

import java.util.*;

/**
 * @author Ampflower
 * @since 0.3.2
 **/
public interface ClassDefiner {

    static ClassDefiner getInstance() {
        {
            final ClassDefiner instance = ClassUtils.instance;
            if (instance != null) {
                return instance;
            }
        }

        final ServiceLoader<ClassDefiner> loader = ServiceLoader.load(ClassDefiner.class);
        final List<ClassDefiner> definers = new ArrayList<>();

        final Iterator<ClassDefiner> itr = loader.iterator();

        ClassDefiner definer = null;

        while (itr.hasNext()) try {
            definer = itr.next();

            // Try to load the definer. If it throws an error, it must be ignored.
            definer.tryLoad();

            definers.add(definer);
        } catch (ReflectiveOperationException roe) {
            ClassUtils.logger.debug("Ignoring {}", definer, roe);
        }

        if (definers.isEmpty()) {
            throw new UnsupportedOperationException("JVM has no usable hot-loading methods.");
        }


        definers.sort(Comparator.comparingInt(ClassDefiner::priority));


        return ClassUtils.instance = definers.get(0);
    }

    void tryLoad() throws ReflectiveOperationException;

    Class<?> define(ClassLoader loader, byte[] bytecode) throws Throwable;

    default boolean requiresLoader() {
        return false;
    }

    default boolean mangles() {
        return false;
    }

    default int priority() {
        return 0;
    }
}
