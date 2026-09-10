class TryDemo {

    static int divide(int a, int b) {
        srctracer.Trace._FUNC(1);
        int __srctracer_tryidx$0 = srctracer.Trace._TRY();
        try {
            {
                int __srctracer_ret$1 = a / b;
                srctracer.Trace._RETURN();
                return __srctracer_ret$1;
            }
        } catch (ArithmeticException e) {
            srctracer.Trace._CATCH(__srctracer_tryidx$0);
            {
                int __srctracer_ret$0 = -1;
                srctracer.Trace._RETURN();
                return __srctracer_ret$0;
            }
        }
    }

    static int parseOr(String s, int dflt) {
        srctracer.Trace._FUNC(2);
        int __srctracer_tryidx$1 = srctracer.Trace._TRY();
        try {
            int value = Integer.parseInt(s);
            {
                int __srctracer_ret$4 = value;
                srctracer.Trace._RETURN();
                return __srctracer_ret$4;
            }
        } catch (NumberFormatException e) {
            srctracer.Trace._CATCH(__srctracer_tryidx$1);
            System.out.println("not a number: " + s);
            {
                int __srctracer_ret$2 = dflt;
                srctracer.Trace._RETURN();
                return __srctracer_ret$2;
            }
        } catch (Exception e) {
            srctracer.Trace._CATCH(__srctracer_tryidx$1 + 1);
            System.out.println("unexpected: " + e);
            {
                int __srctracer_ret$3 = -1;
                srctracer.Trace._RETURN();
                return __srctracer_ret$3;
            }
        }
    }

    static void logSafe(String msg) {
        srctracer.Trace._FUNC(3);
        int __srctracer_tryidx$2 = srctracer.Trace._TRY();
        try {
            System.out.println(msg);
            srctracer.Trace._TRY_END();
        } catch (Exception e) {
            srctracer.Trace._CATCH(__srctracer_tryidx$2);
            // swallow
        }
    }

    public static void main(String[] args) {
        srctracer.Trace.trace_start("TryDemo");
        try {
            srctracer.Trace._FUNC(4);
            int q = divide(10, args.length);
            int n = parseOr(args.length > 0 ? args[0] : "x", 99);
            logSafe("q=" + q + " n=" + n);
        } finally {
            srctracer.Trace.trace_end();
        }
    }
}
