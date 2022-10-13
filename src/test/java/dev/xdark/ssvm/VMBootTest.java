package dev.xdark.ssvm;

import dev.xdark.ssvm.execution.VMException;
import org.junit.jupiter.api.Test;

public final class VMBootTest {

	@Test
	public void testBoot() {
		VirtualMachine vm = new VirtualMachine(anInterface, memoryAllocator, memoryManager, helper, classDefiner, threadManager, fileDescriptorManager, nativeLibraryManager, stringPool, managementInterface, timeManager, classLoaders, executionEngine, publicOperations, trustedOperations, publicLinkResolver, trustedLinkResolver, mirrorFactory, objectSynchronizer, properties, env);
		try {
			vm.bootstrap();
		} catch (IllegalStateException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof VMException) {
				vm.getHelper().toJavaException(((VMException) cause).getOop()).printStackTrace();
			}
			throw ex;
		}
	}
}
