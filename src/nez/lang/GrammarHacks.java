package nez.lang;

import java.util.AbstractList;

import nez.ast.SourcePosition;
import nez.ast.Symbol;
import nez.lang.expr.ExpressionCommons;
import nez.lang.expr.NonTerminal;
import nez.util.UList;

public abstract class GrammarHacks extends AbstractList<Production> {
	protected SourcePosition getSourcePosition() {
		return null;
	}

	public abstract void addProduction(Production p);

	public final Production newProduction(SourcePosition s, int flag, String name, Expression e) {
		Production p = new Production(s, flag, (Grammar) this, name, e);
		addProduction(p);
		return p;
	}

	public final Production newProduction(String name, Expression e) {
		return newProduction(getSourcePosition(), 0, name, e);
	}

	public final NonTerminal newNonTerminal(SourcePosition s, String name) {
		return ExpressionCommons.newNonTerminal(s, (Grammar) this, name);
	}

	public final Expression newEmpty() {
		return ExpressionCommons.newEmpty(getSourcePosition());
	}

	public final Expression newFailure() {
		return ExpressionCommons.newFailure(getSourcePosition());
	}

	public final Expression newByteChar(int ch) {
		return ExpressionCommons.newCbyte(getSourcePosition(), false, ch);
	}

	public final Expression newAnyChar() {
		return ExpressionCommons.newCany(getSourcePosition(), false);
	}

	public final Expression newString(String text) {
		return ExpressionCommons.newString(getSourcePosition(), text);
	}

	public final Expression newCharSet(String text) {
		return ExpressionCommons.newCharSet(getSourcePosition(), text);
	}

	public final Expression newByteMap(boolean[] byteMap) {
		return ExpressionCommons.newCset(getSourcePosition(), false, byteMap);
	}

	public final Expression newSequence(Expression... seq) {
		UList<Expression> l = new UList<Expression>(new Expression[8]);
		for (Expression p : seq) {
			ExpressionCommons.addSequence(l, p);
		}
		return ExpressionCommons.newPsequence(getSourcePosition(), l);
	}

	public final Expression newChoice(Expression... seq) {
		UList<Expression> l = new UList<Expression>(new Expression[8]);
		for (Expression p : seq) {
			ExpressionCommons.addChoice(l, p);
		}
		return ExpressionCommons.newPchoice(getSourcePosition(), l);
	}

	public final Expression newOption(Expression... seq) {
		return ExpressionCommons.newPoption(getSourcePosition(), newSequence(seq));
	}

	public final Expression newRepetition(Expression... seq) {
		return ExpressionCommons.newPzero(getSourcePosition(), newSequence(seq));
	}

	public final Expression newRepetition1(Expression... seq) {
		return ExpressionCommons.newPone(getSourcePosition(), newSequence(seq));
	}

	public final Expression newAnd(Expression... seq) {
		return ExpressionCommons.newPand(getSourcePosition(), newSequence(seq));
	}

	public final Expression newNot(Expression... seq) {
		return ExpressionCommons.newPnot(getSourcePosition(), newSequence(seq));
	}

	// PEG4d
	public final Expression newMatch(Expression... seq) {
		return ExpressionCommons.newTdetree(getSourcePosition(), newSequence(seq));
	}

	public final Expression newLink(Expression... seq) {
		return ExpressionCommons.newTlink(getSourcePosition(), null, newSequence(seq));
	}

	public final Expression newLink(Symbol label, Expression... seq) {
		return ExpressionCommons.newTlink(getSourcePosition(), label, newSequence(seq));
	}

	public final Expression newNew(Expression... seq) {
		return ExpressionCommons.newNewCapture(getSourcePosition(), false, null, newSequence(seq));
	}

	// public final Expression newLeftNew(Expression ... seq) {
	// return GrammarFactory.newNew(getSourcePosition(), true,
	// newSequence(seq));
	// }

	public final Expression newTagging(String tag) {
		return ExpressionCommons.newTtag(getSourcePosition(), Symbol.tag(tag));
	}

	public final Expression newReplace(String msg) {
		return ExpressionCommons.newTreplace(getSourcePosition(), msg);
	}

	// Conditional Parsing
	// <if FLAG>
	// <on FLAG e>
	// <on !FLAG e>

	public final Expression newIfFlag(String flagName) {
		return ExpressionCommons.newXif(getSourcePosition(), flagName);
	}

	public final Expression newXon(String flagName, Expression... seq) {
		return ExpressionCommons.newXon(getSourcePosition(), true, flagName, newSequence(seq));
	}

	public final Expression newBlock(Expression... seq) {
		return ExpressionCommons.newXblock(getSourcePosition(), newSequence(seq));
	}

	public final Expression newDefSymbol(NonTerminal n) {
		return ExpressionCommons.newXsymbol(getSourcePosition(), n);
	}

	public final Expression newIsSymbol(NonTerminal n) {
		return ExpressionCommons.newXis(getSourcePosition(), n);
	}

	public final Expression newIsaSymbol(NonTerminal n) {
		return ExpressionCommons.newXisa(getSourcePosition(), n);
	}

	public final Expression newExists(String table, String symbol) {
		return ExpressionCommons.newXexists(getSourcePosition(), Symbol.tag(table), symbol);
	}

	public final Expression newLocal(String table, Expression... seq) {
		return ExpressionCommons.newXlocal(getSourcePosition(), Symbol.tag(table), newSequence(seq));
	}

	public final Expression newScan(int number, Expression scan, Expression repeat) {
		return null;
	}

	public final Expression newRepeat(Expression e) {
		return null;

	}

}
