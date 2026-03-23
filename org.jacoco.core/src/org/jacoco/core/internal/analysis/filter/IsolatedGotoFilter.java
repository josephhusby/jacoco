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

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Filters GOTO instructions that are the sole bytecode instruction on their
 * source line and represent a {@code break} or {@code continue} statement. This
 * handles the case where a short-circuit {@code ||} condition causes the
 * preceding conditional branch to jump directly to the target, bypassing the
 * GOTO. JaCoCo would otherwise report the GOTO as a missed instruction even
 * though the branch was taken.
 */
public final class IsolatedGotoFilter implements IFilter {

	@Override
	public void filter(final MethodNode methodNode,
			final IFilterContext context, final IFilterOutput output) {
		for (final AbstractInsnNode i : methodNode.instructions) {
			if (i.getOpcode() == Opcodes.GOTO) {
				filter(i, output);
			}
		}
	}

	private void filter(final AbstractInsnNode gotoInsn,
			final IFilterOutput output) {
		// Check that the GOTO is the only real instruction on its line.
		// Walk backwards to find the line number node and the preceding
		// real instruction.
		int gotoLine = -1;
		AbstractInsnNode prev = gotoInsn.getPrevious();
		AbstractInsnNode firstOnLine = gotoInsn;

		// Walk backwards past non-opcode nodes to find the line and
		// any preceding real instruction on the same line.
		while (prev != null) {
			if (prev instanceof LineNumberNode) {
				gotoLine = ((LineNumberNode) prev).line;
				firstOnLine = prev;
				break;
			}
			if (prev.getOpcode() != -1) {
				// Real instruction before the GOTO on the same implicit
				// line — this GOTO is not isolated.
				return;
			}
			firstOnLine = prev;
			prev = prev.getPrevious();
		}

		if (gotoLine == -1) {
			return;
		}

		// Verify the GOTO is the ONLY real instruction between its
		// LINENUMBER node and the next LINENUMBER or end of method.
		AbstractInsnNode next = gotoInsn.getNext();
		while (next != null) {
			if (next instanceof LineNumberNode) {
				break;
			}
			if (next.getOpcode() != -1) {
				// Another real instruction on the same line
				return;
			}
			next = next.getNext();
		}

		// The GOTO is the sole instruction on its line.
		// Check that a preceding conditional branch reaches the same
		// target (indicating short-circuit || or && that can bypass
		// this GOTO).
		final JumpInsnNode jump = (JumpInsnNode) gotoInsn;
		final LabelNode gotoTarget = jump.label;

		if (hasConditionalBranchToTarget(gotoInsn, gotoTarget)) {
			output.ignore(firstOnLine, gotoInsn);
		}
	}

	/**
	 * Checks whether there is a conditional branch instruction before the given
	 * GOTO that jumps to the same target (or an earlier unconditional GOTO to
	 * the same target). This handles the short-circuit pattern where
	 * {@code if (a || b) break;} compiles the first condition as a direct jump
	 * to the break target.
	 */
	private boolean hasConditionalBranchToTarget(
			final AbstractInsnNode gotoInsn, final LabelNode target) {
		AbstractInsnNode node = gotoInsn.getPrevious();
		while (node != null) {
			final int opcode = node.getOpcode();
			if (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ACMPNE) {
				// Conditional branch — check if it targets the same
				// label
				if (((JumpInsnNode) node).label == target) {
					return true;
				}
			}
			if (opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) {
				if (((JumpInsnNode) node).label == target) {
					return true;
				}
			}
			// Also check for an unconditional GOTO to the same target
			// (another break/continue path)
			if (opcode == Opcodes.GOTO) {
				if (((JumpInsnNode) node).label == target) {
					return true;
				}
				// Stop searching past unconditional control flow
				break;
			}
			// Stop at return/throw
			if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
				break;
			}
			if (opcode == Opcodes.ATHROW) {
				break;
			}
			// Stop at switch instructions
			if (opcode == Opcodes.TABLESWITCH
					|| opcode == Opcodes.LOOKUPSWITCH) {
				break;
			}
			node = node.getPrevious();
		}
		return false;
	}

}
