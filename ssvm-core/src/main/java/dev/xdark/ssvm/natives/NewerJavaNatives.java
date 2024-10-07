package dev.xdark.ssvm.natives;

import dev.xdark.ssvm.VirtualMachine;
import dev.xdark.ssvm.api.MethodInvoker;
import dev.xdark.ssvm.api.VMInterface;
import dev.xdark.ssvm.execution.Result;
import dev.xdark.ssvm.mirror.type.InstanceClass;
import dev.xdark.ssvm.symbol.Symbols;
import dev.xdark.ssvm.value.InstanceValue;
import dev.xdark.ssvm.value.ObjectValue;
import dev.xdark.ssvm.value.SimpleArrayValue;
import lombok.experimental.UtilityClass;
import org.objectweb.asm.Type;

/**
 * Java native patches for Java 9+.
 */
@UtilityClass
public class NewerJavaNatives {
	/**
	 * @param vm VM instance.
	 */
	public void init(VirtualMachine vm) {
		VMInterface vmi = vm.getInterface();
		Symbols symbols = vm.getSymbols();

		// SSVM manages its own memory, and this conflicts with it. Stubbing it out keeps everyone
		// happy.
		InstanceClass bits = (InstanceClass) vm.findBootstrapClass("java/nio/Bits");
		if (bits != null) {
			vmi.setInvoker(bits.getMethod("reserveMemory", "(JJ)V"), MethodInvoker.noop());
		}

		InstanceClass varHandle = (InstanceClass) vm.findBootstrapClass("java/lang/invoke/VarHandle");
		vmi.setInvoker(varHandle, "get", "([Ljava/lang/Object;)Ljava/lang/Object;", ctx -> {
			InstanceValue instance = ctx.getLocals().loadReference(0);
			//System.out.println(vm.getOperations().toString(instance));

			ObjectValue varOwner = ctx.getLocals().loadReference(1);
			if (varOwner instanceof SimpleArrayValue) {
				SimpleArrayValue array = (SimpleArrayValue) varOwner;

				int index = ctx.getLocals().loadInt(2);

				//System.out.println("Array: " + array);
				//System.out.println("Index: " + index);

				//ObjectValue value = vm.getMemoryManager().nullValue();
				Type arrayType = array.getJavaClass().getComponentType().getType();
				if (arrayType == Type.BOOLEAN_TYPE) {
					ctx.setResult(array.getBoolean(index) ? 1 : 0);
				} else if (arrayType == Type.BYTE_TYPE) {
					ctx.setResult(array.getByte(index));
				} else if (arrayType == Type.SHORT_TYPE) {
					ctx.setResult(array.getShort(index));
				} else if (arrayType == Type.CHAR_TYPE) {
					ctx.setResult(array.getChar(index));
				} else if (arrayType == Type.INT_TYPE) {
					ctx.setResult(array.getInt(index));
				} else if (arrayType == Type.LONG_TYPE) {
					ctx.setResult(array.getLong(index));
				} else if (arrayType == Type.FLOAT_TYPE) {
					ctx.setResult(array.getFloat(index));
				} else if (arrayType == Type.DOUBLE_TYPE) {
					ctx.setResult(array.getDouble(index));
				} else {
					ctx.setResult(array.getReference(index));
				}
				//System.out.println(vm.getMemoryManager().arrayBaseOffset(array));
				//ObjectValue value = array.getReference(index);

				//ctx.setResult(value == null ? vm.getMemoryManager().nullValue() : value);
				return Result.ABORT;
			}

			ctx.setResult(vm.getMemoryManager().nullValue());
			return Result.ABORT;
		});

		// TODO: It is broken
		vmi.setInvoker(varHandle, "set", "([Ljava/lang/Object;)V", ctx -> {
			InstanceValue instance = ctx.getLocals().loadReference(0);
			//System.out.println(vm.getOperations().toString(instance));

			ObjectValue varOwner = ctx.getLocals().loadReference(1);
			if (varOwner instanceof SimpleArrayValue) {
				SimpleArrayValue array = (SimpleArrayValue) varOwner;

				int index = ctx.getLocals().loadInt(2);
				ObjectValue value = ctx.getLocals().loadReference(3);

				//System.out.println("Array: " + array);
				//System.out.println("Index: " + index);

				array.setReference(index, value);
				return Result.ABORT;
			}

			ctx.setResult(vm.getMemoryManager().nullValue());
			return Result.ABORT;
		});
	}
}
