/*******************************************************************************
 * Copyright (c) 2009, 2017 Mountainminds GmbH & Co. KG and Contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Evgeny Mandrikov - initial API and implementation
 *
 *******************************************************************************/
package org.jacoco.core.internal.analysis;

import org.jacoco.core.internal.instr.InstrSupport;
import org.junit.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FiltersTest implements Filters.IOutput {

	private MethodNode target;

	private final List<AbstractInsnNode> ignored = new ArrayList<AbstractInsnNode>();
	private final List<AbstractInsnNode> ignoredJumpTargets = new ArrayList<AbstractInsnNode>();
	private final Map<AbstractInsnNode, AbstractInsnNode> remappedJumps = new HashMap<AbstractInsnNode, AbstractInsnNode>();

	@Test
	public void ecj() {
		target = new MethodNode(InstrSupport.ASM_API_VERSION) {
			{
				Label h1 = new Label();
				Label h2 = new Label();
				Label dflt = new Label();
				Label cases = new Label();
				visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String",
						"hashCode", "()I", false);
				visitTableSwitchInsn(0, 2, dflt, h1, h2);
				visitLabel(h1);
				visitJumpInsn(Opcodes.IFNE, cases);
				visitJumpInsn(Opcodes.IFNE, cases);
				visitJumpInsn(Opcodes.GOTO, dflt);
				visitLabel(h2);
				visitJumpInsn(Opcodes.IFNE, cases);
				visitJumpInsn(Opcodes.GOTO, dflt);
				visitLabel(cases);
				visitLabel(dflt);
				visitInsn(Opcodes.RETURN);
			}
		};

		Filters.filter(target, this);

		assertArrayEquals(new AbstractInsnNode[] { i(2), i(3), i(4), i(5), i(6),
				i(7), i(8) }, ignored.toArray());

		assertArrayEquals(new AbstractInsnNode[] { i(3), i(7) },
				ignoredJumpTargets.toArray());

		assertTrue(remappedJumps.get(i(3)).equals(i(1)));
		assertTrue(remappedJumps.get(i(4)).equals(i(1)));
		assertTrue(remappedJumps.get(i(7)).equals(i(1)));
		assertEquals(3, remappedJumps.size());
	}

	@Test
	public void javac() {
		target = new MethodNode(InstrSupport.ASM_API_VERSION) {
			{
				Label h1 = new Label();
				Label h1_2 = new Label();
				Label h2 = new Label();
				Label after_h = new Label();
				Label cases = new Label();
				visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String",
						"hashCode", "()I", false);
				visitLookupSwitchInsn(after_h, new int[] { 97, 98 },
						new Label[] { h1, h2 });
				visitLabel(h1);
				visitJumpInsn(Opcodes.IFEQ, h1_2);
				visitLabel(h1_2);
				visitJumpInsn(Opcodes.IFEQ, after_h);
				visitJumpInsn(Opcodes.GOTO, after_h);
				visitLabel(h2);
				visitJumpInsn(Opcodes.IFEQ, after_h);
				visitJumpInsn(Opcodes.GOTO, after_h);
				visitLabel(after_h);
				visitTableSwitchInsn(0, 2, cases);
				visitLabel(cases);
				visitInsn(Opcodes.RETURN);
			}
		};

		Filters.filter(target, this);

		assertArrayEquals(
				new AbstractInsnNode[] { i(2), i(3), i(4), i(5), i(6), i(1),
						i(2), i(3), i(4), i(5), i(6), i(7), i(8), i(9) },
				ignored.toArray());

		assertArrayEquals(new AbstractInsnNode[] { i(3), i(5) },
				ignoredJumpTargets.toArray());

		assertTrue(remappedJumps.isEmpty());
	}

	private AbstractInsnNode i(final int i) {
		return target.instructions.get(i);
	}

	public void ignore(final AbstractInsnNode instruction) {
		ignored.add(instruction);
	}

	public void ignoreJumpTarget(final AbstractInsnNode instruction) {
		ignoredJumpTargets.add(instruction);
	}

	public void remapJump(final AbstractInsnNode original,
			final AbstractInsnNode remapped) {
		remappedJumps.put(original, remapped);
	}

}
