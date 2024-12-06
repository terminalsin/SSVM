package dev.xdark.ssvm.memory.allocation;

import dev.xdark.ssvm.execution.PanicException;
import dev.xdark.ssvm.util.UnsafeUtil;
import dev.xdark.ssvm.util.VolatileBufferAccess;
import lombok.RequiredArgsConstructor;
import sun.misc.Unsafe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Memory data backed by byte buffer that avoids unaligned volatile access on ARM.
 *
 * On ARM, unaligned accesses via Unsafe can cause SIGBUS. This class checks alignment
 * for volatile reads/writes and falls back to manual byte-by-byte operations if misaligned.
 *
 * Note: This may lose some volatile semantics if misaligned. Always ensure alignment.
 */
@RequiredArgsConstructor
final class BufferMemoryData implements MemoryData {

	private static final Unsafe UNSAFE = UnsafeUtil.get();
	private static final int MEMSET_THRESHOLD = 256;
	private final ByteBuffer buffer;
	private VolatileBufferAccess volatileAccess;

	@Override
	public long readLong(long offset) {
		return buffer.getLong(validate(offset));
	}

	@Override
	public int readInt(long offset) {
		return buffer.getInt(validate(offset));
	}

	@Override
	public char readChar(long offset) {
		return buffer.getChar(validate(offset));
	}

	@Override
	public short readShort(long offset) {
		return buffer.getShort(validate(offset));
	}

	@Override
	public byte readByte(long offset) {
		return buffer.get(validate(offset));
	}

	@Override
	public void writeLong(long offset, long value) {
		buffer.putLong(validate(offset), value);
	}

	@Override
	public void writeInt(long offset, int value) {
		buffer.putInt(validate(offset), value);
	}

	@Override
	public void writeChar(long offset, char value) {
		buffer.putChar(validate(offset), value);
	}

	@Override
	public void writeShort(long offset, short value) {
		buffer.putShort(validate(offset), value);
	}

	@Override
	public void writeByte(long offset, byte value) {
		buffer.put(validate(offset), value);
	}

	@Override
	public long readLongVolatile(long offset) {
		int pos = checkIndex(offset, 8);
		if (!isAligned(pos, 8)) {
			// Fallback: manual assembly
			return assembleLong(pos);
		}
		return volatileAccess().getLong(pos);
	}

	@Override
	public int readIntVolatile(long offset) {
		int pos = checkIndex(offset, 4);
		if (!isAligned(pos, 4)) {
			// Fallback: manual assembly
			return assembleInt(pos);
		}
		return volatileAccess().getInt(pos);
	}

	@Override
	public char readCharVolatile(long offset) {
		int pos = checkIndex(offset, 2);
		if (!isAligned(pos, 2)) {
			// Fallback
			return (char) assembleShort(pos);
		}
		return volatileAccess().getChar(pos);
	}

	@Override
	public short readShortVolatile(long offset) {
		int pos = checkIndex(offset, 2);
		if (!isAligned(pos, 2)) {
			// Fallback
			return assembleShort(pos);
		}
		return volatileAccess().getShort(pos);
	}

	@Override
	public byte readByteVolatile(long offset) {
		// Single byte always aligned
		int pos = checkIndex(offset, 1);
		return volatileAccess().getByte(pos);
	}

	@Override
	public void writeLongVolatile(long offset, long value) {
		int pos = checkIndex(offset, 8);
		if (!isAligned(pos, 8)) {
			disassembleLong(pos, value);
			return;
		}
		volatileAccess().putLong(pos, value);
	}

	@Override
	public void writeIntVolatile(long offset, int value) {
		int pos = checkIndex(offset, 4);
		if (!isAligned(pos, 4)) {
			disassembleInt(pos, value);
			return;
		}
		volatileAccess().putInt(pos, value);
	}

	@Override
	public void writeCharVolatile(long offset, char value) {
		int pos = checkIndex(offset, 2);
		if (!isAligned(pos, 2)) {
			disassembleShort(pos, (short) value);
			return;
		}
		volatileAccess().putChar(pos, value);
	}

	@Override
	public void writeShortVolatile(long offset, short value) {
		int pos = checkIndex(offset, 2);
		if (!isAligned(pos, 2)) {
			disassembleShort(pos, value);
			return;
		}
		volatileAccess().putShort(pos, value);
	}

	@Override
	public void writeByteVolatile(long offset, byte value) {
		// Single byte always aligned
		int pos = checkIndex(offset, 1);
		volatileAccess().putByte(pos, value);
	}

	@Override
	public void set(long offset, long bytes, byte value) {
		ByteBuffer buffer = this.buffer;
		int $offset = validate(offset);
		int $bytes = validate(bytes);
		if ($bytes >= MEMSET_THRESHOLD) {
			byte[] buf = new byte[MEMSET_THRESHOLD];
			Arrays.fill(buf, value);
			ByteBuffer slice = buffer.slice().order(buffer.order());
			slice.position($offset);
			while ($bytes != 0) {
				int len = Math.min($bytes, MEMSET_THRESHOLD);
				slice.put(buf, 0, len);
				$bytes -= len;
			}
		} else {
			while ($bytes-- != 0) {
				buffer.put($offset++, value);
			}
		}
	}

	@Override
	public void write(long srcOffset, MemoryData dst, long dstOffset, long bytes) {
		if (dst instanceof BufferMemoryData) {
			ByteBuffer dstBuf = ((BufferMemoryData) dst).buffer;
			int $srcOffset = validate(srcOffset);
			copyOrder(((ByteBuffer) dstBuf.slice().position(validate(dstOffset))))
				.put((ByteBuffer) buffer.slice().position($srcOffset).limit($srcOffset + validate(bytes)));
		} else {
			int start = validate(dstOffset);
			int $bytes = validate(bytes);
			int $offset = validate(srcOffset);
			ByteBuffer buffer = this.buffer;
			while ($bytes-- != 0) {
				dst.writeByte(start++, buffer.get($offset++));
			}
		}
	}

	@Override
	public void write(long dstOffset, ByteBuffer buffer) {
		copyOrder(((ByteBuffer) this.buffer.slice().position(validate(dstOffset)))).put(buffer);
	}

	@Override
	public void write(long dstOffset, byte[] array, int arrayOffset, int length) {
		copyOrder(((ByteBuffer) buffer.slice().position(validate(dstOffset)))).put(array, arrayOffset, length);
	}

	@Override
	public void write(long dstOffset, long[] array, int arrayOffset, int length) {
		ByteBuffer buffer = this.buffer;
		checkIndex(dstOffset, length * 8);
		if (fastAccess(buffer)) {
			dstOffset += buffer.arrayOffset();
			byte[] data = buffer.array();
			UNSAFE.copyMemory(array, Unsafe.ARRAY_LONG_BASE_OFFSET + arrayOffset * 8L, data, Unsafe.ARRAY_BYTE_BASE_OFFSET + dstOffset, length * 8L);
		} else {
			buffer = buffer.slice().order(buffer.order());
			while (length-- != 0) {
				buffer.putLong(array[arrayOffset++]);
			}
		}
	}

	@Override
	public void write(long dstOffset, double[] array, int arrayOffset, int length) {
		ByteBuffer buffer = this.buffer;
		checkIndex(dstOffset, length * 8);
		if (fastAccess(buffer)) {
			dstOffset += buffer.arrayOffset();
			byte[] data = buffer.array();
			UNSAFE.copyMemory(array, Unsafe.ARRAY_DOUBLE_BASE_OFFSET + arrayOffset * 8L, data, Unsafe.ARRAY_BYTE_BASE_OFFSET + dstOffset, length * 8L);
		} else {
			buffer = buffer.slice().order(buffer.order());
			while (length-- != 0) {
				buffer.putDouble(array[arrayOffset++]);
			}
		}
	}

	@Override
	public void write(long dstOffset, int[] array, int arrayOffset, int length) {
		ByteBuffer buffer = this.buffer;
		checkIndex(dstOffset, length * 4);
		if (fastAccess(buffer)) {
			dstOffset += buffer.arrayOffset();
			byte[] data = buffer.array();
			UNSAFE.copyMemory(array, Unsafe.ARRAY_INT_BASE_OFFSET + arrayOffset * 4L, data, Unsafe.ARRAY_BYTE_BASE_OFFSET + dstOffset, length * 4L);
		} else {
			buffer = buffer.slice().order(buffer.order());
			while (length-- != 0) {
				buffer.putInt(array[arrayOffset++]);
			}
		}
	}

	@Override
	public void write(long dstOffset, float[] array, int arrayOffset, int length) {
		ByteBuffer buffer = this.buffer;
		checkIndex(dstOffset, length * 4);
		if (fastAccess(buffer)) {
			dstOffset += buffer.arrayOffset();
			byte[] data = buffer.array();
			UNSAFE.copyMemory(array, Unsafe.ARRAY_FLOAT_BASE_OFFSET + arrayOffset * 4L, data, Unsafe.ARRAY_BYTE_BASE_OFFSET + dstOffset, length * 4L);
		} else {
			buffer = buffer.slice().order(buffer.order());
			while (length-- != 0) {
				buffer.putFloat(array[arrayOffset++]);
			}
		}
	}

	@Override
	public void write(long dstOffset, char[] array, int arrayOffset, int length) {
		ByteBuffer buffer = this.buffer;
		checkIndex(dstOffset, length * 2);
		if (fastAccess(buffer)) {
			dstOffset += buffer.arrayOffset();
			byte[] data = buffer.array();
			UNSAFE.copyMemory(array, Unsafe.ARRAY_CHAR_BASE_OFFSET + arrayOffset * 2L, data, Unsafe.ARRAY_BYTE_BASE_OFFSET + dstOffset, length * 2L);
		} else {
			buffer = buffer.slice().order(buffer.order());
			while (length-- != 0) {
				buffer.putChar(array[arrayOffset++]);
			}
		}
	}

	@Override
	public void write(long dstOffset, short[] array, int arrayOffset, int length) {
		ByteBuffer buffer = this.buffer;
		checkIndex(dstOffset, length * 2);
		if (fastAccess(buffer)) {
			dstOffset += buffer.arrayOffset();
			byte[] data = buffer.array();
			UNSAFE.copyMemory(array, Unsafe.ARRAY_SHORT_BASE_OFFSET + arrayOffset * 2L, data, Unsafe.ARRAY_BYTE_BASE_OFFSET + dstOffset, length * 2L);
		} else {
			buffer = buffer.slice().order(buffer.order());
			while (length-- != 0) {
				buffer.putShort(array[arrayOffset++]);
			}
		}
	}

	@Override
	public void write(long dstOffset, boolean[] array, int arrayOffset, int length) {
		ByteBuffer buffer = this.buffer;
		checkIndex(dstOffset, length);
		if (fastAccess(buffer)) {
			dstOffset += buffer.arrayOffset();
			byte[] data = buffer.array();
			UNSAFE.copyMemory(array, Unsafe.ARRAY_BOOLEAN_BASE_OFFSET + arrayOffset, data, Unsafe.ARRAY_BYTE_BASE_OFFSET + dstOffset, length);
		} else {
			buffer = buffer.slice().order(buffer.order());
			while (length-- != 0) {
				buffer.put((byte) (array[arrayOffset++] ? 1 : 0));
			}
		}
	}

	@Override
	public void read(long srcOffset, byte[] array, int arrayOffset, int length) {
		checkIndex(srcOffset, length);
		ByteBuffer buffer = this.buffer.slice();
		buffer.position((int) srcOffset);
		buffer.get(array, arrayOffset, length);
	}

	@Override
	public void read(long srcOffset, long[] array, int arrayOffset, int length) {
		checkIndex(srcOffset, length * 8);
		ByteBuffer buffer = this.buffer;
		if (fastAccess(buffer)) {
			srcOffset += buffer.arrayOffset();
			byte[] data = buffer.array();
			UNSAFE.copyMemory(data, Unsafe.ARRAY_BYTE_BASE_OFFSET + srcOffset, array, Unsafe.ARRAY_LONG_BASE_OFFSET + arrayOffset * 8L, length * 8L);
		} else {
			buffer = buffer.slice().order(buffer.order());
			buffer.position((int) srcOffset);
			while (length-- != 0) {
				array[arrayOffset++] = buffer.getLong();
			}
		}
	}

	@Override
	public void read(long srcOffset, double[] array, int arrayOffset, int length) {
		checkIndex(srcOffset, length * 8);
		ByteBuffer buffer = this.buffer;
		if (fastAccess(buffer)) {
			srcOffset += buffer.arrayOffset();
			byte[] data = buffer.array();
			UNSAFE.copyMemory(data, Unsafe.ARRAY_BYTE_BASE_OFFSET + srcOffset, array, Unsafe.ARRAY_DOUBLE_BASE_OFFSET + arrayOffset * 8L, length * 8L);
		} else {
			buffer = buffer.slice().order(buffer.order());
			buffer.position((int) srcOffset);
			while (length-- != 0) {
				array[arrayOffset++] = buffer.getDouble();
			}
		}
	}

	@Override
	public void read(long srcOffset, int[] array, int arrayOffset, int length) {
		checkIndex(srcOffset, length * 4);
		ByteBuffer buffer = this.buffer;
		if (fastAccess(buffer)) {
			srcOffset += buffer.arrayOffset();
			byte[] data = buffer.array();
			UNSAFE.copyMemory(data, Unsafe.ARRAY_BYTE_BASE_OFFSET + srcOffset, array, Unsafe.ARRAY_INT_BASE_OFFSET + arrayOffset * 4L, length * 4L);
		} else {
			buffer = buffer.slice().order(buffer.order());
			buffer.position((int) srcOffset);
			while (length-- != 0) {
				array[arrayOffset++] = buffer.getInt();
			}
		}
	}

	@Override
	public void read(long srcOffset, float[] array, int arrayOffset, int length) {
		checkIndex(srcOffset, length * 4);
		ByteBuffer buffer = this.buffer;
		if (fastAccess(buffer)) {
			srcOffset += buffer.arrayOffset();
			byte[] data = buffer.array();
			UNSAFE.copyMemory(data, Unsafe.ARRAY_BYTE_BASE_OFFSET + srcOffset, array, Unsafe.ARRAY_FLOAT_BASE_OFFSET + arrayOffset * 4L, length * 4L);
		} else {
			buffer = buffer.slice().order(buffer.order());
			buffer.position((int) srcOffset);
			while (length-- != 0) {
				array[arrayOffset++] = buffer.getFloat();
			}
		}
	}

	@Override
	public void read(long srcOffset, char[] array, int arrayOffset, int length) {
		checkIndex(srcOffset, length * 2);
		ByteBuffer buffer = this.buffer;
		if (fastAccess(buffer)) {
			srcOffset += buffer.arrayOffset();
			byte[] data = buffer.array();
			UNSAFE.copyMemory(data, Unsafe.ARRAY_BYTE_BASE_OFFSET + srcOffset, array, Unsafe.ARRAY_CHAR_BASE_OFFSET + arrayOffset * 2L, length * 2L);
		} else {
			buffer = buffer.slice().order(buffer.order());
			buffer.position((int) srcOffset);
			while (length-- != 0) {
				array[arrayOffset++] = buffer.getChar();
			}
		}
	}

	@Override
	public void read(long srcOffset, short[] array, int arrayOffset, int length) {
		checkIndex(srcOffset, length * 2);
		ByteBuffer buffer = this.buffer;
		if (fastAccess(buffer)) {
			srcOffset += buffer.arrayOffset();
			byte[] data = buffer.array();
			UNSAFE.copyMemory(data, Unsafe.ARRAY_BYTE_BASE_OFFSET + srcOffset, array, Unsafe.ARRAY_SHORT_BASE_OFFSET + arrayOffset * 2L, length * 2L);
		} else {
			buffer = buffer.slice().order(buffer.order());
			buffer.position((int) srcOffset);
			while (length-- != 0) {
				array[arrayOffset++] = buffer.getShort();
			}
		}
	}

	@Override
	public void read(long srcOffset, boolean[] array, int arrayOffset, int length) {
		checkIndex(srcOffset, length);
		ByteBuffer buffer = this.buffer;
		if (fastAccess(buffer)) {
			srcOffset += buffer.arrayOffset();
			byte[] data = buffer.array();
			UNSAFE.copyMemory(data, Unsafe.ARRAY_BYTE_BASE_OFFSET + srcOffset, array, Unsafe.ARRAY_BOOLEAN_BASE_OFFSET + arrayOffset, length);
		} else {
			buffer = buffer.slice().order(buffer.order());
			buffer.position((int) srcOffset);
			while (length-- != 0) {
				array[arrayOffset++] = buffer.get() != 0;
			}
		}
	}

	@Override
	public void read(long srcOffset, MemoryData data, long dataOffset, int length) {
		ByteBuffer buffer = this.buffer;
		checkIndex(srcOffset, length);
		if (data instanceof BufferMemoryData) {
			ByteBuffer target = ((BufferMemoryData) data).buffer;
			if (fastAccess(target)) {
				srcOffset += buffer.arrayOffset();
				dataOffset += target.arrayOffset();
				byte[] ourData = buffer.array();
				byte[] theirData = target.array();
				UNSAFE.copyMemory(ourData, Unsafe.ARRAY_BYTE_BASE_OFFSET + srcOffset, theirData, Unsafe.ARRAY_BOOLEAN_BASE_OFFSET + dataOffset, length);
				return;
			}
		}
		buffer = buffer.slice();
		buffer.position((int) srcOffset);
		if (length <= MEMSET_THRESHOLD) {
			byte[] tmp = new byte[length];
			buffer.get(tmp);
			data.write(dataOffset, tmp, 0, length);
		} else {
			data.write(dataOffset, (ByteBuffer) buffer.limit(length));
		}
	}

	@Override
	public long length() {
		return buffer.capacity();
	}

	@Override
	public MemoryData slice(long offset, long bytes) {
		int $offset = validate(offset);
		return new SliceMemoryData(this, $offset, validate(bytes));
	}

	private ByteBuffer copyOrder(ByteBuffer buffer) {
		return buffer.order(this.buffer.order());
	}

	private VolatileBufferAccess volatileAccess() {
		VolatileBufferAccess volatileAccess = this.volatileAccess;
		if (volatileAccess == null) {
			return this.volatileAccess = VolatileBufferAccess.wrap(buffer);
		}
		return volatileAccess;
	}

	private int checkIndex(long offset, int count) {
		if (offset + count > buffer.limit() || offset < 0L) {
			throw new PanicException("Segfault");
		}
		return (int) offset;
	}

	private static int validate(long offset) {
		if (offset > Integer.MAX_VALUE || offset < 0L) {
			throw new PanicException("Segfault");
		}
		return (int) offset;
	}

	private static boolean fastAccess(ByteBuffer buffer) {
		return buffer.hasArray() && isNativeOrder(buffer);
	}

	private static boolean isNativeOrder(ByteBuffer buffer) {
		return buffer.order() == ByteOrder.nativeOrder();
	}

	private static boolean isAligned(int pos, int alignment) {
		return (pos & (alignment - 1)) == 0;
	}

	private long assembleLong(int pos) {
		return ((long) buffer.get(pos) & 0xFF)
			| (((long) buffer.get(pos + 1) & 0xFF) << 8)
			| (((long) buffer.get(pos + 2) & 0xFF) << 16)
			| (((long) buffer.get(pos + 3) & 0xFF) << 24)
			| (((long) buffer.get(pos + 4) & 0xFF) << 32)
			| (((long) buffer.get(pos + 5) & 0xFF) << 40)
			| (((long) buffer.get(pos + 6) & 0xFF) << 48)
			| (((long) buffer.get(pos + 7) & 0xFF) << 56);
	}

	private int assembleInt(int pos) {
		return (buffer.get(pos) & 0xFF)
			| ((buffer.get(pos + 1) & 0xFF) << 8)
			| ((buffer.get(pos + 2) & 0xFF) << 16)
			| ((buffer.get(pos + 3) & 0xFF) << 24);
	}

	private short assembleShort(int pos) {
		return (short) ((buffer.get(pos) & 0xFF)
			| ((buffer.get(pos + 1) & 0xFF) << 8));
	}

	private void disassembleLong(int pos, long value) {
		buffer.put(pos,     (byte)( value        & 0xFF));
		buffer.put(pos + 1, (byte)((value >> 8)  & 0xFF));
		buffer.put(pos + 2, (byte)((value >> 16) & 0xFF));
		buffer.put(pos + 3, (byte)((value >> 24) & 0xFF));
		buffer.put(pos + 4, (byte)((value >> 32) & 0xFF));
		buffer.put(pos + 5, (byte)((value >> 40) & 0xFF));
		buffer.put(pos + 6, (byte)((value >> 48) & 0xFF));
		buffer.put(pos + 7, (byte)((value >> 56) & 0xFF));
	}

	private void disassembleInt(int pos, int value) {
		buffer.put(pos,     (byte)( value       & 0xFF));
		buffer.put(pos + 1, (byte)((value >> 8) & 0xFF));
		buffer.put(pos + 2, (byte)((value >>16) & 0xFF));
		buffer.put(pos + 3, (byte)((value >>24) & 0xFF));
	}

	private void disassembleShort(int pos, short value) {
		buffer.put(pos,     (byte)( value      & 0xFF));
		buffer.put(pos + 1, (byte)((value>>8)  & 0xFF));
	}
}
