package sun.misc;

import java.lang.reflect.Field;

/**
 * Compile-time stub of the runtime's {@code sun.misc.Unsafe}.
 * It is only on the compile classpath; the real class comes from the device's boot classpath.
 */
public final class Unsafe {
    private Unsafe() {
    }

    public native long objectFieldOffset(Field field);

    public native boolean getBoolean(Object obj, long offset);

    public native void putBoolean(Object obj, long offset, boolean value);

    public native byte getByte(Object obj, long offset);

    public native void putByte(Object obj, long offset, byte value);

    public native char getChar(Object obj, long offset);

    public native void putChar(Object obj, long offset, char value);

    public native short getShort(Object obj, long offset);

    public native void putShort(Object obj, long offset, short value);

    public native int getInt(Object obj, long offset);

    public native void putInt(Object obj, long offset, int value);

    public native long getLong(Object obj, long offset);

    public native void putLong(Object obj, long offset, long value);

    public native float getFloat(Object obj, long offset);

    public native void putFloat(Object obj, long offset, float value);

    public native double getDouble(Object obj, long offset);

    public native void putDouble(Object obj, long offset, double value);

    public native Object getObject(Object obj, long offset);

    public native void putObject(Object obj, long offset, Object value);
}
