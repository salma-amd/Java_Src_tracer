package srctracer.instrumenter;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;

import java.util.Optional;

/**
 * Inserts {@code srctracer.Trace.*} calls at every control-flow point in the AST.
 * Assumes {@link BlockWrappingVisitor} has already run.
 */
class InstrumenterVisitor extends ModifierVisitor<Void> {

    int nextFuncId = 1;
    int nextSwitchId = 0;
    int nextTmpId = 0;

    int methods = 0;
    int ifs = 0;
    int returns = 0;
    int loops = 0;
    int switches = 0;
    int tries = 0;
    int mains = 0;

    // ---- Method / constructor entry ----

    @Override
    public Visitable visit(MethodDeclaration md, Void a) {
        super.visit(md, a);
        if (md.getBody().isEmpty()) return md;

        int funcId = nextFuncId++;
        methods++;
        Statement funcCall = parseStatement(
                "srctracer.Trace._FUNC(" + funcId + ");");

        if (isMainMethod(md)) {
            wrapMainWithLifecycle(md, funcCall);
            mains++;
        } else {
            md.getBody().get().addStatement(0, funcCall);
        }
        return md;
    }

    private void wrapMainWithLifecycle(MethodDeclaration md, Statement funcCall) {
        BlockStmt original = md.getBody().get();
        String name = enclosingTypeName(md);

        BlockStmt tryBlock = new BlockStmt();
        tryBlock.addStatement(funcCall);
        for (Statement s : original.getStatements()) {
            tryBlock.addStatement(s.clone());
        }

        BlockStmt finallyBlock = new BlockStmt();
        finallyBlock.addStatement(parseStatement("srctracer.Trace.trace_end();"));

        TryStmt tryStmt = new TryStmt();
        tryStmt.setTryBlock(tryBlock);
        tryStmt.setFinallyBlock(finallyBlock);

        BlockStmt newBody = new BlockStmt();
        newBody.addStatement(parseStatement(
                "srctracer.Trace.trace_start(\"" + name + "\");"));
        newBody.addStatement(tryStmt);

        md.setBody(newBody);
    }

    private static boolean isMainMethod(MethodDeclaration md) {
        if (!md.getNameAsString().equals("main")) return false;
        if (!md.isStatic()) return false;
        if (!md.getType().toString().equals("void")) return false;
        if (md.getParameters().size() != 1) return false;
        String pt = md.getParameter(0).getType().toString();
        return pt.equals("String[]") || pt.equals("java.lang.String[]");
    }

    private static String enclosingTypeName(MethodDeclaration md) {
        Node cur = md.getParentNode().orElse(null);
        while (cur != null) {
            if (cur instanceof TypeDeclaration<?> td) return td.getNameAsString();
            cur = cur.getParentNode().orElse(null);
        }
        return "instrumented";
    }

    @Override
    public Visitable visit(ConstructorDeclaration cd, Void a) {
        super.visit(cd, a);
        BlockStmt body = cd.getBody();
        int idx = !body.getStatements().isEmpty()
                && body.getStatement(0) instanceof ExplicitConstructorInvocationStmt
                ? 1 : 0;
        insertFuncCall(body, idx);
        return cd;
    }

    // ---- If / else ----

    @Override
    public Visitable visit(IfStmt n, Void a) {
        super.visit(n, a);

        ((BlockStmt) n.getThenStmt()).addStatement(0, parseCall("_IF"));

        BlockStmt elseBlock = n.getElseStmt()
                .map(s -> (BlockStmt) s)
                .orElseGet(() -> {
                    BlockStmt b = new BlockStmt();
                    n.setElseStmt(b);
                    return b;
                });
        elseBlock.addStatement(0, parseCall("_ELSE"));

        ifs++;
        return n;
    }

    // ---- Return ----

    @Override
    public Visitable visit(ReturnStmt n, Void a) {
        super.visit(n, a);

        if (isInsideLambda(n)) return n;

        BlockStmt replacement = new BlockStmt();
        Optional<Expression> expr = n.getExpression();

        if (expr.isEmpty()) {
            replacement.addStatement(parseCall("_RETURN"));
            replacement.addStatement(new ReturnStmt());
        } else {
            Optional<Type> retType = findEnclosingReturnType(n);
            if (retType.isEmpty()) return n;

            String tmp = "__srctracer_ret$" + nextTmpId++;
            replacement.addStatement(parseStatement(
                    retType.get().toString() + " " + tmp + " = " + expr.get().toString() + ";"));
            replacement.addStatement(parseCall("_RETURN"));
            replacement.addStatement(parseStatement("return " + tmp + ";"));
        }
        returns++;
        return replacement;
    }

    // ---- Loops ----

    @Override
    public Visitable visit(WhileStmt n, Void a) {
        super.visit(n, a);
        instrumentLoop(n, (BlockStmt) n.getBody());
        return n;
    }

    @Override
    public Visitable visit(DoStmt n, Void a) {
        super.visit(n, a);
        instrumentLoop(n, (BlockStmt) n.getBody());
        return n;
    }

    @Override
    public Visitable visit(ForStmt n, Void a) {
        super.visit(n, a);
        instrumentLoop(n, (BlockStmt) n.getBody());
        return n;
    }

    @Override
    public Visitable visit(ForEachStmt n, Void a) {
        super.visit(n, a);
        instrumentLoop(n, (BlockStmt) n.getBody());
        return n;
    }

    private void instrumentLoop(Statement loopStmt, BlockStmt body) {
        body.addStatement(0, parseCall("_LOOP_BODY"));
        insertAfter(loopStmt, parseCall("_LOOP_END"));
        loops++;
    }

    // ---- Break ----

    @Override
    public Visitable visit(BreakStmt n, Void a) {
        super.visit(n, a);
        if (n.getLabel().isPresent()) return n;
        if (isBreakForSwitch(n)) return n;
        insertBefore(n, parseCall("_BREAK"));
        return n;
    }

    // ---- Switch ----

    @Override
    public Visitable visit(SwitchStmt n, Void a) {
        super.visit(n, a);

        int switchId = nextSwitchId++;
        String flag = "__srctracer_switch$" + switchId;

        insertBefore(n, parseStatement("boolean " + flag + " = true;"));

        NodeList<SwitchEntry> entries = n.getEntries();
        for (int i = 0; i < entries.size(); i++) {
            SwitchEntry entry = entries.get(i);
            Statement caseRecord = parseStatement(
                    "if (" + flag + ") { srctracer.Trace._CASE(" + i + "); "
                            + flag + " = false; }");
            entry.getStatements().add(0, caseRecord);
        }
        switches++;
        return n;
    }

    // ---- Try / catch ----

    @Override
    public Visitable visit(TryStmt n, Void a) {
        super.visit(n, a);

        BlockStmt tryBlock = n.getTryBlock();
        tryBlock.addStatement(0, parseCall("_TRY"));

        if (!alwaysExits(tryBlock)) {
            tryBlock.addStatement(parseCall("_TRY_END"));
        }

        NodeList<CatchClause> catches = n.getCatchClauses();
        for (int i = 0; i < catches.size(); i++) {
            BlockStmt catchBody = catches.get(i).getBody();
            catchBody.addStatement(0, parseStatement(
                    "srctracer.Trace._CATCH(" + i + ");"));
        }

        tries++;
        return n;
    }

    private static boolean alwaysExits(Statement s) {
        if (s instanceof ReturnStmt) return true;
        if (s instanceof ThrowStmt) return true;
        if (s instanceof BlockStmt b) {
            if (b.getStatements().isEmpty()) return false;
            return alwaysExits(b.getStatement(b.getStatements().size() - 1));
        }
        if (s instanceof IfStmt i) {
            return i.getElseStmt().isPresent()
                    && alwaysExits(i.getThenStmt())
                    && alwaysExits(i.getElseStmt().get());
        }
        return false;
    }

    // ---- Helpers ----

    private void insertFuncCall(BlockStmt body, int index) {
        int id = nextFuncId++;
        methods++;
        body.addStatement(index, parseStatement(
                "srctracer.Trace._FUNC(" + id + ");"));
    }

    static Statement parseCall(String method) {
        return parseStatement("srctracer.Trace." + method + "();");
    }

    static Statement parseStatement(String code) {
        return StaticJavaParser.parseStatement(code);
    }

    private static void insertBefore(Node n, Statement newStmt) {
        Node parent = n.getParentNode().orElse(null);
        if (parent instanceof BlockStmt block) {
            int idx = block.getStatements().indexOf(n);
            if (idx >= 0) block.addStatement(idx, newStmt);
        } else if (parent instanceof SwitchEntry entry) {
            int idx = entry.getStatements().indexOf(n);
            if (idx >= 0) entry.getStatements().add(idx, newStmt);
        }
    }

    private static void insertAfter(Node n, Statement newStmt) {
        Node parent = n.getParentNode().orElse(null);
        if (parent instanceof BlockStmt block) {
            int idx = block.getStatements().indexOf(n);
            if (idx >= 0) block.addStatement(idx + 1, newStmt);
        } else if (parent instanceof SwitchEntry entry) {
            int idx = entry.getStatements().indexOf(n);
            if (idx >= 0) entry.getStatements().add(idx + 1, newStmt);
        }
    }

    private static boolean isInsideLambda(Node n) {
        Node cur = n.getParentNode().orElse(null);
        while (cur != null) {
            if (cur instanceof LambdaExpr) return true;
            if (cur instanceof MethodDeclaration) return false;
            if (cur instanceof ConstructorDeclaration) return false;
            cur = cur.getParentNode().orElse(null);
        }
        return false;
    }

    private static boolean isBreakForSwitch(BreakStmt n) {
        Node cur = n.getParentNode().orElse(null);
        while (cur != null) {
            if (cur instanceof SwitchStmt) return true;
            if (cur instanceof WhileStmt
                    || cur instanceof DoStmt
                    || cur instanceof ForStmt
                    || cur instanceof ForEachStmt) return false;
            cur = cur.getParentNode().orElse(null);
        }
        return false;
    }

    private static Optional<Type> findEnclosingReturnType(Node n) {
        Node cur = n.getParentNode().orElse(null);
        while (cur != null) {
            if (cur instanceof MethodDeclaration md) return Optional.of(md.getType());
            if (cur instanceof ConstructorDeclaration) return Optional.empty();
            if (cur instanceof LambdaExpr) return Optional.empty();
            cur = cur.getParentNode().orElse(null);
        }
        return Optional.empty();
    }
}
