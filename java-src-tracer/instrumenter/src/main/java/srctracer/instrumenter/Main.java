package srctracer.instrumenter;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Main {

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: instrumenter <input.java> <output.java>");
            System.exit(1);
        }

        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);

        CompilationUnit cu = StaticJavaParser.parse(input);

        // Pass 1: wrap single-statement loop/conditional bodies in BlockStmt so
        // sibling-insertion (needed for _LOOP_END) works in pass 2.
        cu.accept(new BlockWrappingVisitor(), null);

        // Pass 2: actual trace-call insertion.
        InstrumenterVisitor v = new InstrumenterVisitor();
        cu.accept(v, null);

        Files.writeString(output, cu.toString());
        System.out.println("Wrote: " + output
                + " (" + v.methods + " methods, "
                + v.ifs + " if, "
                + v.returns + " return, "
                + v.loops + " loop, "
                + v.switches + " switch, "
                + v.tries + " try, "
                + v.mains + " main wrapped)");
    }
}
