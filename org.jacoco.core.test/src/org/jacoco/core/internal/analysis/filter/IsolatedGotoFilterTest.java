/*******************************************************************************
 * Copyright (c) 2009, 2026 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Evgeny Mandrikov - initial API and implementation
 *
 *******************************************************************************/
package org.jacoco.core.internal.analysis.filter;

import org.jacoco.core.internal.instr.InstrSupport;
import org.junit.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Unit tests for {@link IsolatedGotoFilter}.
 */
public class IsolatedGotoFilterTest extends FilterTestBase {

	private final IFilter filter = new IsolatedGotoFilter();

	/**
	 * <pre>
	 * if (kill.get() || Thread.currentThread().isInterrupted()) {
	 * 	break;
	 * }
	 * </pre>
	 *
	 * javac compiles this so that kill.get() true jumps directly to L_end
	 * (bypassing the GOTO on the break line). The GOTO only fires when
	 * isInterrupted() is true. Filter should ignore the break GOTO since it
	 * shares a target with the IFNE.
	 */
	@Test
	public void should_ignore_break_with_short_circuit_or() {
		final MethodNode m = new MethodNode(InstrSupport.ASM_API_VERSION, 0,
				"Example", "()V", null, null);

		final Label loopEnd = new Label();
		final Label skipBreak = new Label();

		// Line 6: if (kill.get() || ...)
		m.visitLineNumber(6, new Label());
		m.visitVarInsn(Opcodes.ALOAD, 2);
		m.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
				"java/util/concurrent/atomic/AtomicBoolean", "get", "()Z",
				false);
		m.visitJumpInsn(Opcodes.IFNE, loopEnd); // short-circuit to end

		m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread",
				"currentThread", "()Ljava/lang/Thread;", false);
		m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread",
				"isInterrupted", "()Z", false);
		m.visitJumpInsn(Opcodes.IFEQ, skipBreak); // if false, skip
													// break

		// Line 7: break;
		final Label breakLineLabel = new Label();
		m.visitLineNumber(7, breakLineLabel);
		final AbstractInsnNode breakLineNode = m.instructions.getLast();
		m.visitJumpInsn(Opcodes.GOTO, loopEnd);
		final AbstractInsnNode breakGoto = m.instructions.getLast();

		// Line 9: System.out.println(cmd);
		m.visitLabel(skipBreak);
		m.visitLineNumber(9, new Label());
		m.visitVarInsn(Opcodes.ALOAD, 4);
		m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream",
				"println", "(Ljava/lang/String;)V", false);

		m.visitLabel(loopEnd);
		m.visitLineNumber(11, new Label());
		m.visitInsn(Opcodes.RETURN);

		filter.filter(m, context, output);

		assertIgnored(m, new Range(breakLineNode, breakGoto));
	}

	/**
	 * Same pattern but with a simple {@code if (a) break;} — the IFNE jumps
	 * directly to the target, and the GOTO is redundant.
	 */
	@Test
	public void should_ignore_break_with_simple_if() {
		final MethodNode m = new MethodNode(InstrSupport.ASM_API_VERSION, 0,
				"Example", "()V", null, null);

		final Label loopEnd = new Label();
		final Label skipBreak = new Label();

		m.visitLineNumber(6, new Label());
		m.visitVarInsn(Opcodes.ALOAD, 1);
		m.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
				"java/util/concurrent/atomic/AtomicBoolean", "get", "()Z",
				false);
		m.visitJumpInsn(Opcodes.IFEQ, skipBreak);

		// Line 7: break;
		final Label breakLineLabel = new Label();
		m.visitLineNumber(7, breakLineLabel);
		final AbstractInsnNode breakLineNode = m.instructions.getLast();
		m.visitJumpInsn(Opcodes.GOTO, loopEnd);
		final AbstractInsnNode breakGoto = m.instructions.getLast();

		m.visitLabel(skipBreak);
		m.visitLineNumber(9, new Label());
		m.visitVarInsn(Opcodes.ALOAD, 4);

		m.visitLabel(loopEnd);
		m.visitInsn(Opcodes.RETURN);

		filter.filter(m, context, output);

		// Simple if: IFEQ skipBreak does NOT target loopEnd directly,
		// so the filter should NOT fire.
		assertIgnored(m);
	}

	/**
	 * A GOTO that has other instructions on the same line should NOT be
	 * filtered.
	 */
	@Test
	public void should_not_ignore_goto_with_other_instructions_on_line() {
		final MethodNode m = new MethodNode(InstrSupport.ASM_API_VERSION, 0,
				"Example", "()V", null, null);

		final Label loopEnd = new Label();
		final Label skipBreak = new Label();

		m.visitLineNumber(6, new Label());
		m.visitVarInsn(Opcodes.ALOAD, 1);
		m.visitJumpInsn(Opcodes.IFNE, loopEnd);

		// Line 7: log.info("x"); break;
		m.visitLineNumber(7, new Label());
		m.visitVarInsn(Opcodes.ALOAD, 5);
		m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "Logger", "info",
				"(Ljava/lang/String;)V", false);
		m.visitJumpInsn(Opcodes.GOTO, loopEnd);

		m.visitLabel(skipBreak);
		m.visitLineNumber(9, new Label());
		m.visitVarInsn(Opcodes.ALOAD, 4);

		m.visitLabel(loopEnd);
		m.visitInsn(Opcodes.RETURN);

		filter.filter(m, context, output);

		assertIgnored(m);
	}

	/**
	 * A GOTO without a line number should NOT be filtered.
	 */
	@Test
	public void should_not_ignore_goto_without_line_number() {
		final MethodNode m = new MethodNode(InstrSupport.ASM_API_VERSION, 0,
				"Example", "()V", null, null);

		final Label end = new Label();

		// No line number before the GOTO
		m.visitJumpInsn(Opcodes.GOTO, end);

		m.visitLabel(end);
		m.visitInsn(Opcodes.RETURN);

		filter.filter(m, context, output);

		assertIgnored(m);
	}

	/**
	 * A GOTO where no preceding conditional targets the same label should NOT
	 * be filtered.
	 */
	@Test
	public void should_not_ignore_goto_without_matching_conditional() {
		final MethodNode m = new MethodNode(InstrSupport.ASM_API_VERSION, 0,
				"Example", "()V", null, null);

		final Label target1 = new Label();
		final Label target2 = new Label();

		m.visitLineNumber(5, new Label());
		m.visitVarInsn(Opcodes.ALOAD, 1);
		m.visitJumpInsn(Opcodes.IFNE, target1); // targets different
												// label

		m.visitLineNumber(7, new Label());
		m.visitJumpInsn(Opcodes.GOTO, target2); // GOTO different target

		m.visitLabel(target1);
		m.visitInsn(Opcodes.RETURN);
		m.visitLabel(target2);
		m.visitInsn(Opcodes.RETURN);

		filter.filter(m, context, output);

		assertIgnored(m);
	}
}
