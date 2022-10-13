package dev.xdark.ssvm.execution.rewrite;

import dev.xdark.ssvm.asm.VMFieldInsnNode;
import dev.xdark.ssvm.execution.ExecutionContext;
import dev.xdark.ssvm.execution.InstructionProcessor;
import dev.xdark.ssvm.execution.Result;
import dev.xdark.ssvm.mirror.type.InstanceClass;
import dev.xdark.ssvm.mirror.member.JavaField;

/**
 * Fast path processor for PUTSTATIC.
 *
 * @author xDark
 */
public final class PutStaticDoubleProcessor implements InstructionProcessor<VMFieldInsnNode> {

	@Override
	public Result execute(VMFieldInsnNode insn, ExecutionContext<?> ctx) {
		JavaField field = insn.getResolved();
		InstanceClass klass = field.getOwner();
		klass.getOop().getData().writeLong(field.getOffset(), Double.doubleToRawLongBits(ctx.getStack().popDouble()));
		return Result.CONTINUE;
	}
}
