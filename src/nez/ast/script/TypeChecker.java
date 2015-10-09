package nez.ast.script;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;

import nez.ast.Symbol;
import nez.ast.TreeVisitor2;
import nez.ast.script.TypeSystem.BinaryTypeUnifier;
import nez.ast.script.asm.InterfaceFactory;
import nez.util.StringUtils;
import nez.util.UList;

public class TypeChecker extends TreeVisitor2<nez.ast.script.TypeChecker.Undefined> implements CommonSymbols {
	ScriptContext context;
	TypeSystem typeSystem;

	// TypeScope scope;

	public TypeChecker(ScriptContext context, TypeSystem typeSystem) {
		// super(TypedTree.class);
		this.context = context;
		this.typeSystem = typeSystem;
		init(new Undefined());
	}

	public class Undefined {
		public Type accept(TypedTree node) {
			node.formatSourceMessage("error", "unsupproted type rule #" + node.getTag());
			typeSystem.TODO("TypeChecker for %s", node);
			return void.class;
		}
	}

	public class Error {
		public Type type(TypedTree t) {
			context.log(t.getText(_msg, ""));
			return void.class;
		}
	}

	FunctionBuilder function = null;

	public final FunctionBuilder enterFunction(String name) {
		this.function = new FunctionBuilder(this.function, name);
		return this.function;
	}

	public final void exitFunction() {
		this.function = this.function.pop();
	}

	public final boolean inFunction() {
		return this.function != null;
	}

	public Type visit(TypedTree node) {
		Type c = find(node).accept(node);
		if (c != null) {
			node.setType(c);
		}
		return c;
	}

	private String name(Type t) {
		return TypeSystem.name(t);
	}

	public void enforceType(Type req, TypedTree node, Symbol label) {
		TypedTree unode = node.get(label, null);
		if (unode == null) {
			throw this.error(node, "syntax error: %s is required", label);
		}
		visit(unode);
		node.set(label, this.typeSystem.enforceType(req, unode));
	}

	public void typed(TypedTree node, Type c) {
		node.setType(c);
	}

	/* TopLevel */
	public class Source extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			Type t = null;
			for (int i = 0; i < node.size(); i++) {
				TypedTree sub = node.get(i);
				try {
					t = visit(sub);
				} catch (TypeCheckerException e) {
					sub = e.errorTree;
					node.set(i, sub);
					t = sub.getType();
				}
			}
			return t;
		}
	}

	public class Import extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeImport(node);
		}
	}

	public Type typeImport(TypedTree node) {
		StringBuilder sb = new StringBuilder();
		join(sb, node.get(0)); // FIXME: konoha.nez
		String path = sb.toString();
		try {
			typeSystem.importStaticClass(path);
		} catch (ClassNotFoundException e) {
			throw error(node, "undefined class name: %s", path);
		}
		node.done();
		return void.class;
	}

	private void join(StringBuilder sb, TypedTree node) {
		TypedTree prefix = node.get(_prefix);
		if (prefix.size() == 2) {
			join(sb, prefix);
		} else {
			sb.append(prefix.toText());
		}
		sb.append(".").append(node.getText(_name, null));
	}

	/* FuncDecl */
	private static Type[] EmptyTypes = new Type[0];

	public class FuncDecl extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeFuncDecl(node);
		}
	}

	public Type typeFuncDecl(TypedTree node) {
		String name = node.getText(_name, null);
		TypedTree bodyNode = node.get(_body, null);
		Type returnType = typeSystem.resolveType(node.get(_type, null), null);
		Type[] paramTypes = EmptyTypes;
		TypedTree params = node.get(_param, null);
		if (node.has(_param)) {
			int c = 0;
			paramTypes = new Type[params.size()];
			for (TypedTree p : params) {
				paramTypes[c] = typeSystem.resolveType(p.get(_type, null), Object.class);
				c++;
			}
		}
		/* prototye declration */
		if (bodyNode == null) {
			Class<?> funcType = this.typeSystem.getFuncType(returnType, paramTypes);
			if (typeSystem.hasGlobalVariable(name)) {
				throw error(node.get(_name), "duplicated name: %s", name);
			}
			typeSystem.newGlobalVariable(funcType, name);
			node.done();
			return void.class;
		}
		FunctionBuilder f = this.enterFunction(name);
		if (returnType != null) {
			f.setReturnType(returnType);
			typed(node.get(_type), returnType);
		}
		if (node.has(_param)) {
			int c = 0;
			for (TypedTree sub : params) {
				String pname = sub.getText(_name, null);
				f.setVarType(pname, paramTypes[c]);
				typed(sub, paramTypes[c]);
			}
		}
		f.setParameterTypes(paramTypes);
		try {
			visit(bodyNode);
		} catch (TypeCheckerException e) {
			node.set(_body, e.errorTree);
		}
		this.exitFunction();
		if (f.getReturnType() == null) {
			f.setReturnType(void.class);
		}
		typed(node.get(_name), f.getReturnType());
		return void.class;
	}

	public class Return extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeReturn(node);
		}
	}

	public Type typeReturn(TypedTree node) {
		if (!inFunction()) {
			throw this.error(node, "return must be inside function");
		}
		Type t = this.function.getReturnType();
		if (t == null) { // type inference
			if (node.has(_expr)) {
				this.function.setReturnType(visit(node.get(_expr)));
			} else {
				this.function.setReturnType(void.class);
			}
			return void.class;
		}
		if (t == void.class) {
			if (node.size() > 0) {
				node.removeSubtree();
			}
		} else {
			this.enforceType(t, node, _expr);
		}
		return void.class;
	}

	/* Statement */

	public class Block extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			if (inFunction()) {
				function.beginLocalVarScope();
			}
			typeStatementList(node);
			if (inFunction()) {
				function.endLocalVarScope();
			}
			return void.class;
		}
	}

	public class StatementList extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeStatementList(node);
		}
	}

	public Type typeStatementList(TypedTree node) {
		for (int i = 0; i < node.size(); i++) {
			TypedTree sub = node.get(i);
			try {
				visit(sub);
			} catch (TypeCheckerException e) {
				sub = e.errorTree;
				node.set(i, sub);
			}
		}
		return void.class;
	}

	public class Assert extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			enforceType(boolean.class, node, _cond);
			if (node.has(_msg)) {
				enforceType(String.class, node, _msg);
			} else {
				String msg = node.get(_cond).formatSourceMessage("assert", "failed");
				node.make(_cond, node.get(_cond), _msg, node.newStringConst(msg));
			}
			node.setMethod(Hint.StaticInvocation, typeSystem.AssertionMethod, null);
			return void.class;
		}
	}

	public class If extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			enforceType(boolean.class, node, _cond);
			visit(node.get(_then));
			if (node.has(_else)) {
				visit(node.get(_else));
			}
			return void.class;
		}
	}

	public class Conditional extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			enforceType(boolean.class, node, _cond);
			Type then_t = visit(node.get(_then));
			Type else_t = visit(node.get(_else));
			if (then_t != else_t) {
				enforceType(then_t, node, _else);
			}
			return then_t;
		}
	}

	public class While extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			enforceType(boolean.class, node, _cond);
			visit(node.get(_body));
			return void.class;
		}
	}

	public class Continue extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return void.class;
		}
	}

	public class Break extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return void.class;
		}
	}

	public class For extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			if (inFunction()) {
				function.beginLocalVarScope();
			}
			if (node.has(_init)) {
				visit(node.get(_init));
			}
			if (node.has(_cond)) {
				enforceType(boolean.class, node, _cond);
			}
			if (node.has(_iter)) {
				visit(node.get(_iter));
			}
			visit(node.get(_body));
			if (inFunction()) {
				function.endLocalVarScope();
			}
			return void.class;
		}
	}

	public class ForEach extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeForEach(node);
		}
	}

	public Type typeForEach(TypedTree node) {
		Type req_t = null;
		if (node.has(_type)) {
			req_t = this.typeSystem.resolveType(node.get(_type), null);
		}
		String name = node.getText(_name, "");
		req_t = typeIterator(req_t, node.get(_iter));
		if (inFunction()) {
			this.function.beginLocalVarScope();
		}
		this.function.setVarType(name, req_t);
		visit(node.get(_body));
		if (inFunction()) {
			this.function.endLocalVarScope();
		}
		return void.class;
	}

	protected Type[] EmptyArgument = new Type[0];

	private Type typeIterator(Type req_t, TypedTree node) {
		Type iter_t = visit(node.get(_iter));
		Method m = typeSystem.resolveObjectMethod(req_t, this.bufferMatcher, "iterator", EmptyArgument, null, null);
		if (m != null) {
			TypedTree iter = node.newInstance(_MethodApply, 0, null);
			iter.make(_recv, node.get(_iter), _param, node.newInstance(_List, 0, null));
			iter_t = iter.setMethod(Hint.MethodApply, m, this.bufferMatcher);
			// TODO
			// if(req_t != null) {
			// }
			// node.set(index, node)
		}
		throw error(node.get(_iter), "unsupported iterator for %s", name(iter_t));
	}

	public class VarDecl extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeVarDecl(node);
		}
	}

	public Type typeVarDecl(TypedTree node) {
		String name = node.getText(_name, null);
		Type type = typeSystem.resolveType(node.get(_type, null), null);
		if (type != null) {
			if (node.has(_expr)) {
				enforceType(type, node, _expr);
			}
		} else { /* type inference from the expression */
			if (!node.has(_expr)) { // untyped
				this.typeSystem.reportWarning(node.get(_name), "type is ungiven");
				type = Object.class;
			} else {
				type = visit(node.get(_expr, null));
			}
		}
		typed(node.get(_name), type); // name is typed

		if (this.inFunction()) {
			// TRACE("local variable");
			this.function.setVarType(name, type);
			return void.class;
		}
		// TRACE("global variable");
		GlobalVariable gv = typeSystem.getGlobalVariable(name);
		if (gv != null) {
			if (gv.getType() != type) {
				throw error(node.get(_name), "already defined name: %s as %s", name, name(gv.getType()));
			}
		} else {
			gv = typeSystem.newGlobalVariable(type, name);
		}
		if (!node.has(_expr)) {
			node.done();
			return void.class;
		}
		// Assign
		node.rename(_VarDecl, _Assign);
		return node.setField(Hint.SetField, gv.field);
	}

	/* StatementExpression */

	public class Expression extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return visit(node.get(0));
		}
	}

	/* Expression */

	public class Name extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			Type t = tryCheckNameType(node, true);
			if (t == null) {
				String name = node.toText();
				throw error(node, "undefined name: %s", name);
			}
			return t;
		}
	}

	private Type tryCheckNameType(TypedTree node, boolean rewrite) {
		String name = node.toText();
		if (this.inFunction()) {
			if (this.function.containsVariable(name)) {
				return this.function.getVarType(name);
			}
		}
		if (this.typeSystem.hasGlobalVariable(name)) {
			GlobalVariable gv = this.typeSystem.getGlobalVariable(name);
			if (rewrite) {
				node.setField(Hint.GetField, gv.field);
			}
			return gv.getType();
		}
		return null;
	}

	public class Assign extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeAssign(node);
		}
	}

	public Type typeAssign(TypedTree node) {
		TypedTree leftnode = node.get(_left);
		if (typeSystem.shellMode && !this.inFunction() && leftnode.is(_Name)) {
			String name = node.getText(_left, null);
			if (!this.typeSystem.hasGlobalVariable(name)) {
				this.typeSystem.newGlobalVariable(Object.class, name);
			}
		}
		if (leftnode.is(_Indexer)) {
			return typeSetIndexer(node, //
					node.get(_left).get(_recv), //
					node.get(_left).get(_param), //
					node.get(_right));
		}
		Type left = visit(leftnode);
		this.enforceType(left, node, _right);

		if (leftnode.hint == Hint.GetField) {
			Field f = leftnode.getField();
			if (Modifier.isFinal(f.getModifiers())) {
				throw error(node.get(_left), "readonly");
			}
			if (!Modifier.isStatic(f.getModifiers())) {
				node.set(_left, leftnode.get(_recv));
				node.rename(_left, _recv);
			}
			node.rename(_right, _expr);
			node.setField(Hint.SetField, f);
		}
		return left;
	}

	/* Expression */

	public class Cast extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeCast(node);
		}
	}

	public Type typeCast(TypedTree node) {
		Type inner = visit(node.get(_expr));
		Type t = this.typeSystem.resolveType(node.get(_type), null);
		if (t == null) {
			throw error(node.get(_type), "undefined type: %s", node.getText(_type, ""));
		}
		Class<?> req = TypeSystem.toClass(t);
		Class<?> exp = TypeSystem.toClass(inner);
		Method m = typeSystem.getCastMethod(exp, req);
		if (m == null) {
			m = typeSystem.getConvertMethod(exp, req);
		}
		if (m != null) {
			node.makeFlattenedList(node.get(_expr));
			return node.setMethod(Hint.StaticInvocation, m, null);
		}
		if (req.isAssignableFrom(exp)) { // upcast
			node.setTag(_UpCast);
			return t;
		}
		if (exp.isAssignableFrom(req)) { // downcast
			node.setTag(_DownCast);
			return t;
		}
		throw error(node.get(_type), "undefined cast: %s => %s", name(inner), name(t));
	}

	// public Type[] typeList(TypedTree node) {
	// Type[] args = new Type[node.size()];
	// for (int i = 0; i < node.size(); i++) {
	// args[i] = type(node.get(i));
	// }
	// return args;
	// }

	public class _Field extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeField(node);
		}
	}

	public Type typeField(TypedTree node) {
		if (isStaticClassRecv(node)) {
			return typeStaticField(node);
		}
		visit(node.get(_recv));
		Class<?> c = node.get(_recv).getClassType();
		String name = node.getText(_name, "");
		java.lang.reflect.Field f = typeSystem.getField(c, name);
		if (f != null) {
			return node.setField(Hint.GetField, f);
		}
		if (typeSystem.isDynamic(c)) {
			return node.setMethod(Hint.StaticInvocation, typeSystem.DynamicGetter, null);
		}
		throw error(node.get(_name), "undefined field %s of %s", name, name(c));
	}

	public Type typeStaticField(TypedTree node) {
		Class<?> c = this.typeSystem.resolveClass(node.get(_recv), null);
		String name = node.getText(_name, "");
		java.lang.reflect.Field f = typeSystem.getField(c, name);
		if (f != null) {
			if (!Modifier.isStatic(f.getModifiers())) {
				throw error(node, "not static field %s of %s", name, name(c));
			}
			return node.setField(Hint.GetField, f);
		}
		throw error(node.get(_name), "undefined field %s of %s", name, name(c));
	}

	public Type typeIndexer(TypedTree node) {
		Type recv_t = visit(node.get(_recv));
		Type[] param_t = this.typeApplyArguments(node.get(_param));
		int start = this.bufferMethods.size();
		Method m = this.typeSystem.resolveObjectMethod(recv_t, this.bufferMatcher, "get", param_t, null, null);
		if (m != null) {
			return this.resolvedMethod(node, Hint.MethodApply, m, bufferMatcher);
		}
		if (this.typeSystem.isDynamic(recv_t)) {
			node.makeFlattenedList(node.get(_recv), node.get(_param));
			return node.setMethod(Hint.StaticInvocation, typeSystem.ObjectIndexer, null);
		}
		return this.undefinedMethod(node, start, "unsupported indexer [] for %s", name(recv_t));
	}

	private Type typeSetIndexer(TypedTree node, TypedTree recv, TypedTree param, TypedTree expr) {
		param.makeFlattenedList(param, expr);
		node.make(_recv, recv, _param, param);
		Type recv_t = visit(node.get(_recv));
		Type[] param_t = this.typeApplyArguments(node.get(_param));
		int start = this.bufferMethods.size();
		this.bufferMatcher.init(recv_t);
		Method m = this.typeSystem.resolveObjectMethod(recv_t, this.bufferMatcher, "set", param_t, this.bufferMethods, node.get(_param));
		if (m != null) {
			return this.resolvedMethod(node, Hint.MethodApply, m, bufferMatcher);
		}
		if (this.typeSystem.isDynamic(recv_t)) {
			node.makeFlattenedList(node.get(_recv), node.get(_param));
			return node.setMethod(Hint.StaticInvocation, typeSystem.ObjectSetIndexer, null);
		}
		return this.undefinedMethod(node, start, "unsupported set indexer [] for %s", name(recv_t));
	}

	public class Apply extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeApply(node);
		}
	}

	public Type typeApply(TypedTree node) {
		String name = node.getText(_name, "");
		TypedTree args = node.get(_param);
		Type[] types = typeApplyArguments(args);
		if (isRecursiveCall(name, args)) {
			TRACE("recrusive call");
			return typeRecursiveApply(node, name, types);
		}
		Type func_t = this.tryCheckNameType(node.get(_name), true);
		if (this.typeSystem.isFuncType(func_t)) {
			return typeFuncApply(node, func_t, types, args);
		}
		int start = this.bufferMethods.size();
		Method m = this.typeSystem.resolveFunctionMethod(name, types, bufferMethods, args);
		return m != null ? this.resolvedMethod(node, Hint.Apply, m, null) //
				: this.undefinedMethod(node, start, "funciton: %s", name);
	}

	private Type[] typeApplyArguments(TypedTree args) {
		Type[] types = new Type[args.size()];
		for (int i = 0; i < args.size(); i++) {
			types[i] = visit(args.get(i));
		}
		return types;
	}

	private boolean isRecursiveCall(String name, TypedTree params) {
		if (inFunction() && name.equals(function.getName())) {
			Type[] paramTypes = function.getParameterTypes();
			if (typeSystem.matchParameters(paramTypes, params)) {
				return true;
			}
		}
		return false;
	}

	private Type typeRecursiveApply(TypedTree node, String name, Type[] params_t) {
		Type returnType = function.getReturnType();
		if (returnType == null) {
			throw error(node, "ambigious return type in recursive call: %s", name);
		}
		node.setHint(Hint.RecursiveApply, returnType);
		return returnType;
	}

	private Type typeFuncApply(TypedTree node, Type func_t, Type[] params_t, TypedTree params) {
		if (typeSystem.isStaticFuncType(func_t)) {
			Class<?>[] p = typeSystem.getFuncParameterTypes(func_t);
			if (this.typeSystem.matchParameters(p, params)) {
				node.rename(_name, _recv);
				return node.setMethod(Hint.MethodApply, Reflector.findInvokeMethod((Class<?>) func_t), null);
			}
			throw error(node, "mismatched %s", Reflector.findInvokeMethod((Class<?>) func_t));
		} else {
			Method m = Reflector.getInvokeFunctionMethod(params.size());
			if (m != null) {
				for (int i = 0; i < params.size(); i++) {
					params.set(i, this.typeSystem.enforceType(Object.class, params.get(i)));
				}
				node.makeFlattenedList(node.get(_name), params);
				return node.setMethod(Hint.StaticInvocation, m, null);
			}
			throw error(node, "unsupported number of parameters: %d", params.size());
		}
	}

	public class MethodApply extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeMethodApply(node);
		}
	}

	public Type typeMethodApply(TypedTree node) {
		if (isStaticClassRecv(node)) {
			return this.typeStaticMethodApply(node);
		}
		Type recv = visit(node.get(_recv));
		String name = node.getText(_name, "");
		TypedTree args = node.get(_param);
		Type[] types = this.typeApplyArguments(args);
		int start = this.bufferMethods.size();
		this.bufferMatcher.init(recv);
		Method m = this.typeSystem.resolveObjectMethod(recv, this.bufferMatcher, name, types, bufferMethods, args);
		if (m != null) {
			this.resolvedMethod(node, Hint.MethodApply, m, bufferMatcher);
		}
		if (typeSystem.isDynamic(recv)) {
			m = Reflector.getInvokeDynamicMethod(node.get(_param).size());
			if (m != null) {
				node.makeFlattenedList(node.get(_recv), node.newStringConst(name), node.get(_param));
				return node.setMethod(Hint.StaticDynamicInvocation, m, null);
			}
		}
		return this.undefinedMethod(node, start, "method %s of %s", name, name(recv));
	}

	private boolean isStaticClassRecv(TypedTree node) {
		if (node.get(_recv).is(_Name)) {
			Type t = this.typeSystem.getType(node.get(_recv).toText());
			return t != null;
		}
		return false;
	}

	public Type typeStaticMethodApply(TypedTree node) {
		Class<?> c = TypeSystem.toClass(this.typeSystem.resolveType(node.get(_recv), null));
		String name = node.getText(_name, "");
		TypedTree args = node.get(_param);
		Type[] types = this.typeApplyArguments(args);
		int start = this.bufferMethods.size();
		Method m = this.typeSystem.resolveStaticMethod(c, name, types, bufferMethods, args);
		return m != null ? this.resolvedMethod(node, Hint.Apply, m, null) //
				: this.undefinedMethod(node, start, "static method %s of %s", name, name(c));
	}

	private Type typeUnary(TypedTree node, String name) {
		Type left = visit(node.get(_expr));
		Type common = typeSystem.PrimitiveType(left);
		if (left != common) {
			left = this.tryPrecast(common, node, _expr);
		}
		Type[] types = new Type[] { left };
		int start = this.bufferMethods.size();
		Method m = this.typeSystem.resolveFunctionMethod(name, types, bufferMethods, node);
		return m != null ? this.resolvedMethod(node, Hint.StaticInvocation, m, null) //
				: this.undefinedMethod(node, start, "operator %s for %s", OperatorNames.name(name), name(left));
	}

	private Type typeBinary(TypedTree node, String name, BinaryTypeUnifier unifier) {
		Type left = visit(node.get(_left));
		Type right = visit(node.get(_right));
		Type common = unifier.unify(typeSystem.PrimitiveType(left), typeSystem.PrimitiveType(right));
		if (left != common) {
			left = this.tryPrecast(common, node, _left);
		}
		if (right != common) {
			right = this.tryPrecast(common, node, _right);
		}
		Type[] types = new Type[] { left, right };
		int start = this.bufferMethods.size();
		Method m = this.typeSystem.resolveFunctionMethod(name, types, bufferMethods, node);
		return m != null ? this.resolvedMethod(node, Hint.StaticInvocation, m, null) //
				: this.undefinedMethod(node, start, "operator %s %s %s", name(left), OperatorNames.name(name), name(right));
	}

	private Type tryPrecast(Type req, TypedTree node, Symbol label) {
		TypedTree unode = node.get(label);
		TypedTree cnode = this.typeSystem.makeCast(req, unode);
		if (unode == cnode) {
			return node.getType();
		}
		node.set(label, cnode);
		return req;
	}

	public class And extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			enforceType(boolean.class, node, _left);
			enforceType(boolean.class, node, _right);
			return boolean.class;
		}
	}

	public class Or extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			enforceType(boolean.class, node, _left);
			enforceType(boolean.class, node, _right);
			return boolean.class;
		}
	}

	public class Not extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			enforceType(boolean.class, node, _expr);
			return typeUnary(node, "opNot");
		}
	}

	public class Instanceof extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			Class<?> c = TypeSystem.toClass(visit(node.get(_left)));
			Class<?> t = typeSystem.resolveClass(node.get(_right), null);
			if (!t.isAssignableFrom(c)) {
				typeSystem.reportWarning(node, "incompatible instanceof operation: %s", name(t));
				node.setConst(boolean.class, false);
			}
			node.setValue(t);
			return boolean.class;
		}
	}

	public class Add extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeBinary(node, "opAdd", TypeSystem.UnifyAdditive);
		}
	}

	public class Sub extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeBinary(node, "opSub", TypeSystem.UnifyAdditive);
		}
	}

	public class Mul extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeBinary(node, "opMul", TypeSystem.UnifyAdditive);
		}
	}

	public class Div extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeBinary(node, "opDiv", TypeSystem.UnifyAdditive);
		}
	}

	public class Mod extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeBinary(node, "opMod", TypeSystem.UnifyAdditive);
		}
	}

	public class Plus extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeUnary(node, "opPlus");
		}
	}

	public class Minus extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeUnary(node, "opMinus");
		}
	}

	public class Equals extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeBinary(node, "opEquals", TypeSystem.UnifyEquator);
		}
	}

	public class NotEquals extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeBinary(node, "opNotEquals", TypeSystem.UnifyEquator);
		}
	}

	public class LessThan extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeBinary(node, "opLessThan", TypeSystem.UnifyComparator);
		}
	}

	public class LessThanEquals extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeBinary(node, "opLessThanEquals", TypeSystem.UnifyComparator);
		}
	}

	public class GreaterThan extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeBinary(node, "opGreaterThan", TypeSystem.UnifyComparator);
		}
	}

	public class GreaterThanEquals extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeBinary(node, "opGreaterThanEquals", TypeSystem.UnifyComparator);
		}
	}

	public class LeftShift extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeBinary(node, "opLeftShift", TypeSystem.UnifyBitwise);
		}
	}

	public class RightShift extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeBinary(node, "opRightShift", TypeSystem.UnifyBitwise);
		}
	}

	public class LogicalRightShift extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeBinary(node, "opLogicalRightShift", TypeSystem.UnifyBitwise);
		}
	}

	public class BitwiseAnd extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeBinary(node, "opBitwiseAnd", TypeSystem.UnifyBitwise);
		}
	}

	public class BitwiseOr extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeBinary(node, "opBitwiseOr", TypeSystem.UnifyBitwise);
		}
	}

	public class BitwiseXor extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeBinary(node, "opBitwiseXor", TypeSystem.UnifyBitwise);
		}
	}

	public class Compl extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeUnary(node, "opCompl");
		}
	}

	public class Null extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return Object.class;
		}
	}

	public class True extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return node.setConst(boolean.class, true);
		}
	}

	public class False extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return node.setConst(boolean.class, false);
		}
	}

	public class _Integer extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			try {
				String n = node.toText().replace("_", "");
				if (n.startsWith("0b") || n.startsWith("0B")) {
					return node.setConst(int.class, Integer.parseInt(n.substring(2), 2));
				} else if (n.startsWith("0x") || n.startsWith("0X")) {
					return node.setConst(int.class, Integer.parseInt(n.substring(2), 16));
				}
				return node.setConst(int.class, Integer.parseInt(n));
			} catch (NumberFormatException e) {
				typeSystem.reportWarning(node, e.getMessage());
			}
			return node.setConst(int.class, 0);
		}
	}

	public class _Long extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			try {
				String n = node.toText();
				return node.setConst(long.class, Long.parseLong(n));
			} catch (NumberFormatException e) {
				typeSystem.reportWarning(node, e.getMessage());
			}
			return node.setConst(long.class, 0L);
		}
	}

	public class _Float extends _Double {
	}

	public class _Double extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			try {
				String n = node.toText();
				return node.setConst(double.class, Double.parseDouble(n));
			} catch (NumberFormatException e) {
				typeSystem.reportWarning(node, e.getMessage());
			}
			return node.setConst(double.class, 0.0);
		}
	}

	public class Text extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return node.setConst(String.class, node.toText());
		}
	}

	public class _String extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			String t = node.toText();
			return node.setConst(String.class, StringUtils.unquoteString(t));
		}
	}

	public class Character extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			String t = StringUtils.unquoteString(node.toText());
			if (t.length() == 1) {
				return node.setConst(char.class, t.charAt(0));
			}
			return node.setConst(String.class, t);
		}
	}

	public class Interpolation extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			for (int i = 0; i < node.size(); i++) {
				TypedTree sub = node.get(i);
				visit(sub);
				if (sub.getType() != Object.class) {
					node.set(i, typeSystem.enforceType(Object.class, sub));
				}
			}
			return node.setMethod(Hint.Unique, typeSystem.InterpolationMethod, null);
		}
	}

	UList<Method> bufferMethods = new UList<Method>(new Method[128]);

	private String methods(UList<Method> bufferMethods, int start) {
		StringBuilder sb = new StringBuilder();
		for (int i = start; i < bufferMethods.size(); i++) {
			sb.append(" ");
			sb.append(bufferMethods.ArrayValues[i]);
		}
		bufferMethods.clear(start);
		return sb.toString();
	}

	private Type resolvedMethod(TypedTree node, Hint hint, Method m, TypeVarMatcher matcher) {
		return node.setMethod(hint, m, matcher);
	}

	private Type undefinedMethod(TypedTree node, int start, String fmt, Object... args) {
		String msg = String.format(fmt, args);
		if (this.bufferMethods.size() > start) {
			msg = "mismatched " + msg + methods(bufferMethods, start);
		} else {
			msg = "undefined " + msg;
		}
		throw error(node, msg);
	}

	// new interface
	private InterfaceFactory factory = new InterfaceFactory();
	private TypeVarMatcher bufferMatcher = new TypeVarMatcher(this.typeSystem);

	private TypeVarMatcher initTypeMatcher(Type recvType) {
		bufferMatcher.init(recvType);
		return bufferMatcher;
	}

	private Type undefinedMethod(TypedTree node, TypeVarMatcher matcher, String fmt, Object... args) {
		String methods = matcher.getErrorMessage();
		String msg = String.format(fmt, args);
		if (methods == null) {
			msg = "undefined " + msg;
		} else {
			msg = "mismatched " + msg + methods;
		}
		throw error(node, msg);
	}

	public class New extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			Type newType = typeSystem.resolveType(node.get(_type), null);
			Type[] paramTypes = typeApplyArguments(node.get(_param));
			TypeVarMatcher matcher = initTypeMatcher(newType);
			Interface inf = typeSystem.resolveConstructor(factory, matcher, newType, paramTypes);
			if (inf == null) {
				inf = matcher.matchCandidate(node.get(_param));
			}
			if (inf != null) {
				return node.setInterface(Hint.Constructor, inf, matcher);
			}
			return undefinedMethod(node, matcher, "constructor %s", name(newType));
		}
	}

	/* array */

	private Type typeCollectionElement(TypedTree node, int step) {
		if (node.size() == 0) {
			return Object.class;
		}
		boolean mixed = false;
		Type elementType = Object.class;
		int shift = step == 2 ? 1 : 0;
		for (int i = 0; i < node.size(); i += step) {
			TypedTree sub = node.get(i + shift);
			Type t = visit(sub);
			if (t == elementType) {
				continue;
			}
			if (elementType == null) {
				elementType = t;
			} else {
				mixed = true;
				elementType = Object.class;
			}
		}
		if (mixed) {
			for (int i = 0; i < node.size(); i += step) {
				TypedTree sub = node.get(i + shift);
				if (sub.getType() != Object.class) {
					node.set(i, typeSystem.enforceType(Object.class, sub));
				}
			}
		}
		return elementType;
	}

	public class Array extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			Type elementType = typeCollectionElement(node, 1);
			Type arrayType = typeSystem.newArrayType(elementType);
			return arrayType;
		}
	}

	public class Set extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			Type elementType = typeCollectionElement(node, 1);
			Type arrayType = typeSystem.newArrayType(elementType);
			return arrayType;
		}
	}

	public class Dict extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			Type elementType = typeCollectionElement(node, 1);
			Type arrayType = typeSystem.newArrayType(elementType);
			return arrayType;
		}
	}

	// Syntax Sugar

	private Type typeSelfAssignment(TypedTree node, Symbol optag) {
		TypedTree op = node.newInstance(optag, 0, null);
		op.make(_left, node.get(_left).dup(), _right, node.get(_right));
		node.set(_right, op);
		node.setTag(_Assign);
		return typeAssign(node);
	}

	public class AssignAdd extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeSelfAssignment(node, _Add);
		}
	}

	public class AssignSub extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeSelfAssignment(node, _Sub);
		}
	}

	public class AssignMul extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeSelfAssignment(node, _Mul);
		}
	}

	public class AssignDiv extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeSelfAssignment(node, _Div);
		}
	}

	public class AssignMod extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeSelfAssignment(node, _Mod);
		}
	}

	public class AssignLeftShift extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeSelfAssignment(node, _LeftShift);
		}
	}

	public class AssignRightShift extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeSelfAssignment(node, _RightShift);
		}
	}

	public class AssignLogicalRightShift extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeSelfAssignment(node, _LogicalRightShift);
		}
	}

	public class AssignBitwiseAnd extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeSelfAssignment(node, _BitwiseAnd);
		}
	}

	public class AssignBitwiseXOr extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeSelfAssignment(node, _BitwiseXor);
		}
	}

	public class AssignBitwiseOr extends Undefined {
		@Override
		public Type accept(TypedTree node) {
			return typeSelfAssignment(node, _BitwiseOr);
		}
	}

	private TypeCheckerException error(TypedTree node, String fmt, Object... args) {
		return this.typeSystem.error(node, fmt, args);
	}

	void TRACE(String fmt, Object... args) {
		typeSystem.TRACE(fmt, args);
	}

	void TODO(String fmt, Object... args) {
		typeSystem.TODO(fmt, args);
	}

	void DEBUG(String fmt, Object... args) {
		typeSystem.DEBUG(fmt, args);
	}

}
