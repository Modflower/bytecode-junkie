package net.gudenau.minecraft.asm.api.v1;

/**
 * The entry point for bytecode transformers.
 * */
public interface RawTransformer{
    /**
     * The name of this transformer.
     *
     * @return The identifier of this transformer
     * */
    Identifier getName();

    /**
     * A quick check to see if this transformer might handle a class.
     *
     * @param name The name of the class
     * @param transformedName The transformed name of the class
     *
     * @return true if the class might get transformed
     * */
    boolean handlesClass(String name, String transformedName);

    /**
     * Transforms a class.
     *
     * @param classBytes The raw class that is being transformer
     * @return The transformed class bytes
     */
    byte[] transform(byte[] classBytes);
}