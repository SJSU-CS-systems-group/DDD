package net.discdd.bundledropbox.cycle;

import java.util.ArrayList;
import java.util.List;

/** Records every {@link RadioDriver} call; the test completes them by hand via the captured callbacks. */
final class FakeRadio implements RadioDriver {
    /** One recorded radio call. */
    static final class Call {
        final String op;
        final long gen;
        final RadioCallback cb;

        Call(String op, long gen, RadioCallback cb) {
            this.op = op;
            this.gen = gen;
            this.cb = cb;
        }

        void ready() {
            cb.ready(gen);
        }

        void fail(String reason) {
            cb.failed(gen, reason);
        }

        void visitFinished(boolean ok) {
            ((VisitCallback) cb).visitFinished(gen, ok);
        }

        @Override
        public String toString() {
            return op + "#" + gen;
        }
    }

    final List<Call> calls = new ArrayList<>();

    Call last() {
        return calls.get(calls.size() - 1);
    }

    List<String> ops() {
        List<String> out = new ArrayList<>();
        for (Call c : calls) out.add(c.op);
        return out;
    }

    @Override
    public void startHosting(long gen, RadioCallback cb) {
        calls.add(new Call("startHosting", gen, cb));
    }

    @Override
    public void stopHosting(long gen, RadioCallback cb) {
        calls.add(new Call("stopHosting", gen, cb));
    }

    @Override
    public void startVisit(long gen, VisitCallback cb) {
        calls.add(new Call("startVisit", gen, cb));
    }

    @Override
    public void stopVisit(long gen, RadioCallback cb) {
        calls.add(new Call("stopVisit", gen, cb));
    }
}
