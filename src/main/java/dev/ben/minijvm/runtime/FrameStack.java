package dev.ben.minijvm.runtime;

import dev.ben.minijvm.exception.StackFaultException;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Call stack (frame stack) representing the thread's active invocation hierarchy (JVMS 2.5.2).
 * Strictly maintains LIFO order, guards against underflow and recursion overflow,
 * and encapsulates its internal mutable storage.
 */
public final class FrameStack {
    public static final int DEFAULT_MAX_DEPTH = 1024;

    private final int maxDepth;
    private final Deque<Frame> frames;

    public FrameStack() {
        this(DEFAULT_MAX_DEPTH);
    }

    public FrameStack(int maxDepth) {
        if (maxDepth <= 0) {
            throw new StackFaultException("FrameStack max depth must be positive: " + maxDepth);
        }
        this.maxDepth = maxDepth;
        this.frames = new ArrayDeque<>();
    }

    public int maxDepth() {
        return maxDepth;
    }

    public int depth() {
        return frames.size();
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    public void push(Frame frame) {
        if (frame == null) {
            throw new StackFaultException("Cannot push null Frame onto FrameStack");
        }
        if (frames.size() >= maxDepth) {
            throw new StackFaultException(
                    String.format("Call stack overflow: maximum call depth of %d exceeded", maxDepth)
            );
        }
        frames.push(frame);
    }

    public Frame pop() {
        if (frames.isEmpty()) {
            throw new StackFaultException("Call stack underflow: cannot pop from empty FrameStack");
        }
        return frames.pop();
    }

    public Frame peek() {
        if (frames.isEmpty()) {
            throw new StackFaultException("Call stack underflow: cannot peek empty FrameStack");
        }
        return frames.peek();
    }

    public Frame current() {
        return peek();
    }

    public void clear() {
        frames.clear();
    }

    /**
     * Returns an unmodifiable snapshot list of frames from top (most recent) to bottom (caller root).
     */
    public List<Frame> toList() {
        return List.copyOf(frames);
    }

    @Override
    public String toString() {
        return String.format("FrameStack[depth=%d/%d, top=%s]", frames.size(), maxDepth, frames.peek());
    }
}
