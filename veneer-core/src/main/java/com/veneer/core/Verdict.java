package com.veneer.core;

/** What a client check concluded; UNKNOWN when it has not run or the client gave no usable answer. */
public enum Verdict {
    DETECTED, NOT_DETECTED, UNKNOWN;

    static Verdict of(boolean detected, boolean checked) {
        return detected ? DETECTED : checked ? NOT_DETECTED : UNKNOWN;
    }
}
