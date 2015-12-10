package nez.x.generator;

import nez.lang.Expression;
import nez.lang.Production;
import nez.lang.expr.Cany;
import nez.lang.expr.Cbyte;
import nez.lang.expr.Cmulti;
import nez.lang.expr.Cset;
import nez.lang.expr.NonTerminal;
import nez.lang.expr.Pand;
import nez.lang.expr.Pchoice;
import nez.lang.expr.Pnot;
import nez.lang.expr.Pone;
import nez.lang.expr.Poption;
import nez.lang.expr.Psequence;
import nez.lang.expr.Pzero;
import nez.lang.expr.Tcapture;
import nez.lang.expr.Tdetree;
import nez.lang.expr.Tlfold;
import nez.lang.expr.Tlink;
import nez.lang.expr.Tnew;
import nez.lang.expr.Treplace;
import nez.lang.expr.Ttag;
import nez.lang.expr.Xblock;
import nez.lang.expr.Xsymbol;
import nez.lang.expr.Xdefindent;
import nez.lang.expr.Xexists;
import nez.lang.expr.Xif;
import nez.lang.expr.Xindent;
import nez.lang.expr.Xis;
import nez.lang.expr.Xlocal;
import nez.lang.expr.Xmatch;
import nez.lang.expr.Xon;
import nez.parser.Parser;
import nez.parser.ParserGrammar;
import nez.parser.ParserGenerator;

public class PegjsGrammarGenerator extends ParserGenerator {

	@Override
	protected String getFileExtension() {
		return "pegjs";
	}

	@Override
	public void visitCmulti(Cmulti p) {
		// TODO Auto-generated method stub

	}

	public void makeHeader(Parser g) {
		L("// The following is generated by the Nez Grammar Generator ");

	}

	public void makeFooter(Parser g) {

	}

	@Override
	protected String name(Production p) {
		return p.getLocalName();
	}

	protected String _Open() {
		return "<";
	};

	protected String _Close() {
		return ">";
	};

	protected String _Delim() {
		return ",";
	};

	public void visitGrouping(Expression e) {
		// W(_OpenGrouping());
		visitExpression(e);
		// W(_CloseGrouping());
	}

	@Override
	public void visitProduction(ParserGrammar gg, Production p) {
		Expression e = p.getExpression();
		L(name(p));
		Begin("");
		L("= ");
		visitExpression(e);
		End("");
	}

	@Override
	public void visitPempty(Expression e) {
	}

	@Override
	public void visitPfail(Expression e) {
	}

	@Override
	public void visitNonTerminal(NonTerminal e) {
		W("" + name(e.getProduction()));
	}

	public String stringfyByte(int byteChar) {
		char c = (char) byteChar;
		switch (c) {
		case '\n':
			return ("'\\n'");
		case '\t':
			return ("'\\t'");
		case '\r':
			return ("'\\r'");
		case '\"':
			return ("\"\\\"\"");
		case '\\':
			return ("'\\\\'");
		}
		return "\"" + c + "\"";
	}

	@Override
	public void visitCbyte(Cbyte e) {
		W(this.stringfyByte(e.byteChar));
	}

	private int searchEndChar(boolean[] b, int s) {
		for (; s < 256; s++) {
			if (!b[s]) {
				return s - 1;
			}
		}
		return 255;
	}

	private void getRangeChar(byte ch, StringBuilder sb) {
		char c = (char) ch;
		switch (c) {
		case '\n':
			sb.append("\\n");
			break;
		case '\t':
			sb.append("'\\t'");
			break;
		case '\r':
			sb.append("'\\r'");
			break;
		case '\'':
			sb.append("'\\''");
			break;
		case '\"':
			sb.append("\"");
			break;
		case '\\':
			sb.append("'\\\\'");
			break;
		}
		sb.append(c);
	}

	@Override
	public void visitCset(Cset e) {
		W("[");
		boolean b[] = e.byteMap;
		for (int start = 0; start < 256; start++) {
			if (b[start]) {
				int end = searchEndChar(b, start + 1);
				if (start == end) {
					W(this.stringfyByte(start));
				} else {
					StringBuilder sb = new StringBuilder();
					getRangeChar((byte) start, sb);
					sb.append("-");
					getRangeChar((byte) end, sb);
					W(sb.toString());
					start = end;
				}
			}
		}
		W("]");
	}

	public void visitString(String s) {
	}

	@Override
	public void visitCany(Cany e) {
		W(".");
	}

	@Override
	public void visitPoption(Poption e) {
		for (Expression sub : e) {
			visitExpression(sub);
		}
		W("?");
	}

	@Override
	public void visitPzero(Pzero e) {
		for (Expression sub : e) {
			visitExpression(sub);
		}
		W("*");
	}

	@Override
	public void visitPone(Pone e) {
		for (Expression sub : e) {
			visitExpression(sub);
		}
		W("+");
	}

	@Override
	public void visitPand(Pand e) {
		W("&");
		for (Expression sub : e) {
			visitExpression(sub);
		}
	}

	@Override
	public void visitPnot(Pnot e) {
		W("!");
		for (Expression sub : e) {
			visitExpression(sub);
		}
	}

	@Override
	public void visitPchoice(Pchoice e) {
		int checkFirst = 0;
		W("(");
		for (Expression sub : e) {
			if (checkFirst > 0) {
				L("/ ");
			}
			visitExpression(sub);
			checkFirst++;
		}
		W(")");
	}

	@Override
	public void visitPsequence(Psequence e) {
		W("(");
		for (Expression sub : e) {
			visitExpression(sub);
			W(" ");
		}
		W(")");
	}

	@Override
	public void visitTnew(Tnew e) {
		for (Expression sub : e) {
			visitExpression(sub);
		}
	}

	@Override
	public void visitTcapture(Tcapture e) {
	}

	@Override
	public void visitTtag(Ttag e) {
	}

	@Override
	public void visitTreplace(Treplace e) {
	}

	@Override
	public void visitTlink(Tlink e) {
		// if(e.index != -1) {
		// C("Link", String.valueOf(e.index), e);
		// }
		// else {
		// C("Link", e);
		// }
		visitExpression(e.get(0));
	}

	@Override
	public void visitUndefined(Expression e) {
		if (e.size() > 0) {
			visitExpression(e.get(0));
		} else {
		}
		// W("<");
		// W(e.getPredicate());
		// for(Expression se : e) {
		// W(" ");
		// visit(se);
		// }
		// W(">");
	}

	@Override
	public void visitXblock(Xblock p) {
		// TODO Auto-generated method stub

	}

	@Override
	public void visitXlocal(Xlocal p) {
		// TODO Auto-generated method stub

	}

	@Override
	public void visitXdef(Xsymbol p) {
		// TODO Auto-generated method stub

	}

	@Override
	public void visitXexists(Xexists p) {
		// TODO Auto-generated method stub

	}

	@Override
	public void visitXmatch(Xmatch p) {
		// TODO Auto-generated method stub

	}

	@Override
	public void visitXis(Xis p) {
		// TODO Auto-generated method stub

	}

	@Override
	public void visitXdefindent(Xdefindent p) {
		// TODO Auto-generated method stub

	}

	@Override
	public void visitXindent(Xindent p) {
		// TODO Auto-generated method stub

	}

	@Override
	public void visitTdetree(Tdetree p) {
		// TODO Auto-generated method stub

	}

	@Override
	public void visitXif(Xif p) {
		// TODO Auto-generated method stub

	}

	@Override
	public void visitXon(Xon p) {
		// TODO Auto-generated method stub

	}

	@Override
	public void visitTlfold(Tlfold p) {
		// TODO Auto-generated method stub

	}

}
