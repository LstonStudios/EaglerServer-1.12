package net.lstonstudios.verity;

/** Tiny recursive-descent calculator: + - * / % ^, parentheses, unary minus. */
final class MathEval {
    private final String s;
    private int pos;

    private MathEval(String s) {
        this.s = s;
    }

    static Double eval(String expr) {
        try {
            MathEval m = new MathEval(expr.replace('x', '*').replace('×', '*').replace('÷', '/')
                    .replaceAll("\\s+", ""));
            double v = m.expr();
            if (m.pos != m.s.length() || Double.isNaN(v) || Double.isInfinite(v)) {
                return null;
            }
            return v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private double expr() {
        double v = term();
        while (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
            char op = s.charAt(pos++);
            double r = term();
            v = op == '+' ? v + r : v - r;
        }
        return v;
    }

    private double term() {
        double v = power();
        while (pos < s.length() && (s.charAt(pos) == '*' || s.charAt(pos) == '/' || s.charAt(pos) == '%')) {
            char op = s.charAt(pos++);
            double r = power();
            v = op == '*' ? v * r : op == '/' ? v / r : v % r;
        }
        return v;
    }

    private double power() {
        double base = unary();
        if (pos < s.length() && s.charAt(pos) == '^') {
            pos++;
            return Math.pow(base, power());
        }
        return base;
    }

    private double unary() {
        if (pos < s.length() && s.charAt(pos) == '-') {
            pos++;
            return -unary();
        }
        if (pos < s.length() && s.charAt(pos) == '+') {
            pos++;
            return unary();
        }
        return atom();
    }

    private double atom() {
        if (pos < s.length() && s.charAt(pos) == '(') {
            pos++;
            double v = expr();
            if (pos >= s.length() || s.charAt(pos) != ')') {
                throw new IllegalStateException("missing )");
            }
            pos++;
            return v;
        }
        int start = pos;
        while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
            pos++;
        }
        if (start == pos) {
            throw new IllegalStateException("number expected");
        }
        return Double.parseDouble(s.substring(start, pos));
    }
}
