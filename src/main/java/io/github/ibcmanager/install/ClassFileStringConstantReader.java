package io.github.ibcmanager.install;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;

/** Reads one compile-time String field constant from a class file without loading or executing it. */
final class ClassFileStringConstantReader {
    private static final int CLASS_MAGIC = 0xCAFEBABE;
    private static final int MAX_CONSTANT_POOL_ENTRIES = 65_535;

    private ClassFileStringConstantReader() { }

    static String read(byte[] classBytes, String fieldName) throws IOException {
        Objects.requireNonNull(classBytes, "classBytes");
        Objects.requireNonNull(fieldName, "fieldName");
        if (classBytes.length < 16) throw new IOException("Class file is truncated");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            if (input.readInt() != CLASS_MAGIC) throw new IOException("Class file has an invalid magic value");
            input.readUnsignedShort(); // minor version
            input.readUnsignedShort(); // major version
            int constantCount = input.readUnsignedShort();
            if (constantCount < 1 || constantCount > MAX_CONSTANT_POOL_ENTRIES) {
                throw new IOException("Class file has an invalid constant-pool size");
            }
            Constant[] constants = new Constant[constantCount];
            for (int index = 1; index < constantCount; index++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1 -> constants[index] = new Utf8Constant(input.readUTF());
                    case 3, 4 -> input.skipNBytes(4);
                    case 5, 6 -> {
                        input.skipNBytes(8);
                        index++; // long and double consume two constant-pool slots
                    }
                    case 7, 8, 16, 19, 20 -> constants[index] = new IndexConstant(tag,
                            input.readUnsignedShort());
                    case 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                    case 15 -> input.skipNBytes(3);
                    default -> throw new IOException("Class file contains unsupported constant-pool tag " + tag);
                }
            }

            input.skipNBytes(6); // access_flags, this_class, super_class
            int interfaceCount = input.readUnsignedShort();
            input.skipNBytes((long) interfaceCount * 2L);

            int fieldCount = input.readUnsignedShort();
            for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
                input.readUnsignedShort(); // access flags
                String name = utf8(constants, input.readUnsignedShort());
                input.readUnsignedShort(); // descriptor
                int attributeCount = input.readUnsignedShort();
                Integer constantValueIndex = null;
                for (int attributeIndex = 0; attributeIndex < attributeCount; attributeIndex++) {
                    String attributeName = utf8(constants, input.readUnsignedShort());
                    long length = Integer.toUnsignedLong(input.readInt());
                    if ("ConstantValue".equals(attributeName)) {
                        if (length != 2L) throw new IOException("Invalid ConstantValue attribute length");
                        constantValueIndex = input.readUnsignedShort();
                    } else {
                        input.skipNBytes(length);
                    }
                }
                if (fieldName.equals(name)) {
                    if (constantValueIndex == null) {
                        throw new IOException("Field " + fieldName + " is not a compile-time constant");
                    }
                    return stringConstant(constants, constantValueIndex);
                }
            }
            throw new IOException("Class file does not contain field " + fieldName);
        } catch (EOFException ex) {
            throw new IOException("Class file is truncated", ex);
        }
    }

    private static String stringConstant(Constant[] constants, int index) throws IOException {
        Constant constant = constant(constants, index);
        if (!(constant instanceof IndexConstant reference) || reference.tag() != 8) {
            throw new IOException("Field constant is not a String");
        }
        return utf8(constants, reference.index());
    }

    private static String utf8(Constant[] constants, int index) throws IOException {
        Constant constant = constant(constants, index);
        if (!(constant instanceof Utf8Constant value)) {
            throw new IOException("Class file contains an invalid UTF-8 constant reference");
        }
        return value.value();
    }

    private static Constant constant(Constant[] constants, int index) throws IOException {
        if (index <= 0 || index >= constants.length || constants[index] == null) {
            throw new IOException("Class file contains an invalid constant-pool reference");
        }
        return constants[index];
    }

    private sealed interface Constant permits Utf8Constant, IndexConstant { }
    private record Utf8Constant(String value) implements Constant { }
    private record IndexConstant(int tag, int index) implements Constant { }
}
