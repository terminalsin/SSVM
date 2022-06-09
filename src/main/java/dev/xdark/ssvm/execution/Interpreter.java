package dev.xdark.ssvm.execution;

import dev.xdark.ssvm.VirtualMachine;
import dev.xdark.ssvm.api.InstructionInterceptor;
import dev.xdark.ssvm.api.VMInterface;
import dev.xdark.ssvm.mirror.InstanceJavaClass;
import dev.xdark.ssvm.mirror.JavaClass;
import dev.xdark.ssvm.mirror.JavaMethod;
import dev.xdark.ssvm.util.AsmUtil;
import dev.xdark.ssvm.value.InstanceValue;
import lombok.experimental.UtilityClass;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.util.List;

/**
 * {@link ExecutionContext} processor.
 *
 * @author xDark
 */
@UtilityClass
public class Interpreter {


	/**
	 * Processes {@link ExecutionContext}.
	 *
	 * @param ctx     Context to process.
	 * @param options Execution options.
	 */
	public void execute(ExecutionContext ctx, ExecutionContextOptions options) {
		JavaMethod jm = ctx.getMethod();
		VMInterface vmi = ctx.getVM().getInterface();
		MethodNode mn = jm.getNode();
		InsnList instructions = mn.instructions;
		List<InstructionInterceptor> interceptors = vmi.getInterceptors();
		boolean updateLineNumbers = options.setLineNumbers();
		exec:
		while (true) {
			try {
				int pos = ctx.getInsnPosition();
				ctx.setInsnPosition(pos + 1);
				AbstractInsnNode insn = instructions.get(pos);
				if (updateLineNumbers && insn instanceof LineNumberNode) {
					ctx.setLineNumber(((LineNumberNode) insn).line);
				}
				for (int i = 0; i < interceptors.size(); i++) {
					if (interceptors.get(i).intercept(ctx, insn) == Result.ABORT) {
						break exec;
					}
				}
				if (insn.getOpcode() == -1) {
					continue;
				}
				InstructionProcessor<AbstractInsnNode> processor = vmi.getProcessor(insn);
				if (processor == null) {
					ctx.getHelper().throwException(ctx.getSymbols().java_lang_InternalError(), "No implemented processor for " + insn.getOpcode());
					continue;
				}
				if (processor.execute(insn, ctx) == Result.ABORT) {
					break;
				}
			} catch (VMException ex) {
				ctx.unwind();
				InstanceValue oop = ex.getOop();
				InstanceJavaClass exceptionType = oop.getJavaClass();
				List<TryCatchBlockNode> tryCatchBlocks = mn.tryCatchBlocks;
				int index = ctx.getInsnPosition() - 1;
				VirtualMachine vm = ctx.getVM();
				// int lastIndex = -1;
				boolean shouldRepeat;
				search:
				do {
					shouldRepeat = false;
					for (int i = 0, j = tryCatchBlocks.size(); i < j; i++) {
						TryCatchBlockNode block = tryCatchBlocks.get(i);
						if (index < AsmUtil.getIndex(block.start) || index > AsmUtil.getIndex(block.end)) {
							continue;
						}
						String type = block.type;
						boolean handle = type == null;
						if (!handle) {
							try {
								JavaClass candidate = vm.findClass(ctx.getOwner().getClassLoader(), type, false);
								handle = candidate.isAssignableFrom(exceptionType);
							} catch (VMException hex) {
								index = AsmUtil.getIndex(block.handler);
								/*
								if (lastIndex == index) {
									throw ex;
								}
								lastIndex = index;
								*/
								shouldRepeat = true;
								continue search;
							}
						}
						if (handle) {
							ctx.getStack().push(oop);
							ctx.setInsnPosition(AsmUtil.getIndex(block.handler));
							continue exec;
						}
					}
				} while (shouldRepeat);
				throw ex;
			}
		}
	}
}
