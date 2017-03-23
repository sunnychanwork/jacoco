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
package org.jacoco.core.internal.analysis.filter;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Filters code that is generated for try-with-resources (Java 7).
 */
public final class TryWithResourcesFilter implements IFilter {

	public void filter(final MethodNode methodNode,
			final IFilterOutput output) {
		for (AbstractInsnNode i = methodNode.instructions
				.getFirst(); i != null; i = i.getNext()) {
			new Matcher(i).match(output);
		}
	}

	static class Matcher {
		private final AbstractInsnNode start;

		private AbstractInsnNode cursor;

		Matcher(final AbstractInsnNode start) {
			this.start = start;
		}

		private enum Pattern {
			ECJ, JAVAC_OPTIMAL, JAVAC_FULL, JAVAC_OMITTED_NULL_CHECK, JAVAC_METHOD,
		}

		/**
		 * @return end of a sequence of instructions that closes resources in
		 *         try-with-resources, if {@link #start} points on a start of
		 *         such sequence, <code>null</code> otherwise
		 */
		public void match(final IFilterOutput output) {
			if (start.getPrevious() == null) {
				return;
			}
			for (final Pattern t : Pattern.values()) {
				cursor = start.getPrevious();
				vars.clear();
				labels.clear();
				owner.clear();
				if (matches(t, output)) {
					break;
				}
			}
		}

		private boolean matches(final Pattern pattern,
				final IFilterOutput output) {
			switch (pattern) {
			case ECJ:
				if (matchEcj(output)) {
					output.ignore(start, cursor);
					return true;
				}
			default:
				return matchJavac(pattern, output);
			}
		}

		private boolean matchJavac(final Matcher.Pattern p, final IFilterOutput output) {
			int resources = 0;
			AbstractInsnNode c = cursor;
			while (nextIsCloseResource(p, "r" + resources)) {
				c = cursor;
				resources++;
			}
			cursor = c;
			if (resources == 0) {
				return false;
			}

			if (!nextIs(Opcodes.GOTO)) {
				cursor = c;
			}

			// TODO finally, return, etc
			final AbstractInsnNode bodyStart = cursor;
			while (!nextIsVar(Opcodes.ASTORE, "primaryExc.r0")) {
				if (cursor == null) {
					// TODO explain
					return false;
				}
			}
			final AbstractInsnNode bodyEnd = cursor.getPrevious().getPrevious();

			for (int r = 0; r < resources; r++) {
				if (r > 0) {
					if (!nextIs(Opcodes.ASTORE)) {
						return false;
					}
					// "primaryExc = t"
					if (!nextIs(Opcodes.ALOAD)) {
						return false;
					}
					if (!nextIsVar(Opcodes.ASTORE, "primaryExc.r" + r)) {
						return false;
					}
				}
				if (!nextIs(Opcodes.ALOAD)) {
					return false;
				}
				if (!nextIs(Opcodes.ATHROW)) {
					return false;
				}

				if (!nextIs(Opcodes.ASTORE)) {
					return false;
				}
				if (!nextIsCloseResource(p, "r" + r)) {
					return false;
				}
				if (!nextIs(Opcodes.ALOAD)) {
					return false;
				}
				if (!nextIs(Opcodes.ATHROW)) {
					return false;
				}
			}

			output.ignore(start, bodyStart);
			output.ignore(bodyEnd, cursor);
			return true;
		}

		private boolean nextIsCloseResource(final Pattern p,
				final String name) {
			switch (p) {
			case JAVAC_METHOD:
			case JAVAC_FULL:
				// "if (r != null)"
				if (!(nextIsVar(Opcodes.ALOAD, name)
						&& nextIs(Opcodes.IFNULL))) {
					return false;
				}
			}
			final String primaryExc = "primaryExc." + name;
			switch (p) {
			case JAVAC_METHOD:
			case JAVAC_OPTIMAL:
				if (nextIsVar(Opcodes.ALOAD, primaryExc)
						&& nextIs(Opcodes.ALOAD)
						&& nextIs(Opcodes.INVOKESTATIC)) {
					final MethodInsnNode m = (MethodInsnNode) cursor;
					return "$closeResource".equals(m.name)
							&& "(Ljava/lang/Throwable;Ljava/lang/AutoCloseable;)V"
									.equals(m.desc);
				}
				return false;
			case JAVAC_FULL:
			case JAVAC_OMITTED_NULL_CHECK:
				return nextIsVar(Opcodes.ALOAD, primaryExc)
						// "if (primaryExc != null)"
						&& nextIs(Opcodes.IFNULL)
						// "r.close()"
						&& nextIsClose(name) && nextIs(Opcodes.GOTO)
						// "catch (Throwable t)"
						&& nextIs(Opcodes.ASTORE)
						// "primaryExc.addSuppressed(suppressedExc)"
						&& nextIsVar(Opcodes.ALOAD, primaryExc)
						&& nextIs(Opcodes.ALOAD) && nextIsAddSuppressed()
						&& nextIs(Opcodes.GOTO)
						// "r.close()"
						&& nextIsClose(name);
			default:
				return false;
			}
		}

		private boolean matchEcj(IFilterOutput output) {
			if (!nextIsEcjClose("r0")) {
				return false;
			}
			final AbstractInsnNode c = cursor;
			next();
			if (cursor.getOpcode() != Opcodes.GOTO) {
				cursor = c;
				return nextIsEcjNoFlowOut();
			}
			cursor = c;

			if (!nextIsJump(Opcodes.GOTO, "r0.end")) {
				return false;
			}
			// "catch (Throwable t)"
			// "primaryExc = t"
			if (!nextIsVar(Opcodes.ASTORE, "primaryExc")) {
				return false;
			}
			if (!nextIsEcjCloseAndThrow("r0")) {
				return false;
			}
			// "catch (Throwable t)"
			int i = 0;
			AbstractInsnNode n = cursor;
			while (!nextIsEcjSuppress("last")) {
				cursor = n;
				i++;
				final String r = "r" + i;
				if (!nextIsLabel("r" + (i - 1) + ".end")) {
					return false;
				}
				if (!nextIsEcjClose(r)) {
					return false;
				}
				if (!nextIsJump(Opcodes.GOTO, r + ".end")) {
					return false;
				}
				if (!nextIsEcjSuppress(r)) {
					return false;
				}
				if (!nextIsEcjCloseAndThrow(r)) {
					return false;
				}
				n = cursor;
			}
			// "throw primaryExc"
			return nextIsVar(Opcodes.ALOAD, "primaryExc")
					&& nextIs(Opcodes.ATHROW)
			// && nextIsLabel("r" + i + ".end")
			;
		}

		private boolean nextIsEcjNoFlowOut() {
			int resources = 1;
			while (true) {
				final AbstractInsnNode c = cursor;
				if (!nextIsEcjClose("r" + resources)) {
					cursor = c;
					// FIXME this excludes finally block
					while (cursor != null
							&& cursor.getOpcode() != Opcodes.ARETURN) {
						next();
					}
					if (cursor == null) {
						return false;
					}
					break;
				}
				resources++;
			}
			// "primaryExc = t"
			if (!nextIsVar(Opcodes.ASTORE, "primaryExc")) {
				return false;
			}
			for (int r = 0; r < resources; r++) {
				if (!nextIsEcjCloseAndThrow("r" + r)) {
					return false;
				}
				if (!nextIsEcjSuppress("r" + r)) {
					return false;
				}
			}
			// "throw primaryExc"
			return nextIsVar(Opcodes.ALOAD, "primaryExc")
					&& nextIs(Opcodes.ATHROW);
		}

		private boolean nextIsEcjClose(final String name) {
			return nextIsVar(Opcodes.ALOAD, name)
					// "if (r != null)"
					&& nextIsJump(Opcodes.IFNULL, name + ".end")
					// "r.close()"
					&& nextIsClose(name);
		}

		private boolean nextIsEcjCloseAndThrow(final String name) {
			return nextIsVar(Opcodes.ALOAD, name)
					// "if (r != null)"
					&& nextIsJump(Opcodes.IFNULL, name)
					// "r.close()"
					&& nextIsClose(name) && nextIsLabel(name)
					&& nextIs(Opcodes.ALOAD) && nextIs(Opcodes.ATHROW);
		}

		private boolean nextIsEcjSuppress(final String name) {
			return nextIsVar(Opcodes.ASTORE, name + ".t")
					// "suppressedExc = t"
					// "if (primaryExc != null)"
					&& nextIsVar(Opcodes.ALOAD, "primaryExc")
					&& nextIsJump(Opcodes.IFNONNULL, name + ".suppressStart")
					// "primaryExc = suppressedExc"
					&& nextIs(Opcodes.ALOAD)
					&& nextIsVar(Opcodes.ASTORE, "primaryExc")
					&& nextIsJump(Opcodes.GOTO, name + ".suppressEnd")
					// "if (primaryExc == suppressedExc)"
					&& nextIsLabel(name + ".suppressStart")
					&& nextIsVar(Opcodes.ALOAD, "primaryExc")
					&& nextIs(Opcodes.ALOAD)
					&& nextIsJump(Opcodes.IF_ACMPEQ, name + ".suppressEnd")
					// "primaryExc.addSuppressed(suppressedExc)"
					&& nextIsAddSuppressed(name + ".t")
					&& nextIsLabel(name + ".suppressEnd");
		}

		private final Map<String, String> owner = new HashMap<String, String>();

		private boolean nextIsClose(final String name) {
			if (!nextIsVar(Opcodes.ALOAD, name)) {
				return false;
			}
			next();
			if (cursor.getOpcode() != Opcodes.INVOKEINTERFACE
					&& cursor.getOpcode() != Opcodes.INVOKEVIRTUAL) {
				return false;
			}
			final MethodInsnNode m = (MethodInsnNode) cursor;
			if (!"close".equals(m.name) || !"()V".equals(m.desc)) {
				return false;
			}
			final String actual = m.owner;
			final String expected = owner.get(name);
			if (expected == null) {
				owner.put(name, actual);
				return true;
			} else {
				return expected.equals(actual);
			}
		}

		private boolean nextIsAddSuppressed() {
			if (!nextIs(Opcodes.INVOKEVIRTUAL)) {
				return false;
			}
			final MethodInsnNode m = (MethodInsnNode) cursor;
			return "java/lang/Throwable".equals(m.owner)
					&& "addSuppressed".equals(m.name);
		}

		private boolean nextIsAddSuppressed(final String name) {
			return nextIsVar(Opcodes.ALOAD, "primaryExc")
					&& nextIsVar(Opcodes.ALOAD, name) && nextIsAddSuppressed();
		}

		private final Map<String, Integer> vars = new HashMap<String, Integer>();

		private boolean nextIsVar(final int opcode, final String name) {
			if (!nextIs(opcode)) {
				return false;
			}
			final int actual = ((VarInsnNode) cursor).var;
			final Integer expected = vars.get(name);
			if (expected == null) {
				vars.put(name, actual);
				return true;
			} else {
				return expected == actual;
			}
		}

		private final Map<String, LabelNode> labels = new HashMap<String, LabelNode>();

		private boolean nextIsJump(final int opcode, final String name) {
			if (!nextIs(opcode)) {
				return false;
			}
			final LabelNode actual = ((JumpInsnNode) cursor).label;
			final LabelNode expected = labels.get(name);
			if (expected == null) {
				labels.put(name, actual);
				return true;
			} else {
				return expected == actual;
			}
		}

		private boolean nextIsLabel(final String name) {
			cursor = cursor.getNext();
			if (cursor.getType() != AbstractInsnNode.LABEL) {
				return false;
			}
			final LabelNode actual = (LabelNode) cursor;
			final LabelNode expected = labels.put(name, actual);
			if (expected == null) {
				labels.put(name, actual);
				return true;
			} else {
				return expected == actual;
			}
		}

		/**
		 * Moves {@link #cursor} to next instruction and returns
		 * <code>true</code> if it has given opcode.
		 */
		private boolean nextIs(final int opcode) {
			next();
			return cursor != null && cursor.getOpcode() == opcode;
		}

		/**
		 * Moves {@link #cursor} to next instruction.
		 */
		private void next() {
			do {
				cursor = cursor.getNext();
			} while (cursor != null
					&& (cursor.getType() == AbstractInsnNode.FRAME
							|| cursor.getType() == AbstractInsnNode.LABEL
							|| cursor.getType() == AbstractInsnNode.LINE));
		}
	}

}
