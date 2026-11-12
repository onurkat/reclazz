package com.onurkat.reclazztest.batch;

/** Owned throw site; SAP's native OCC resolver owns the error representation. */
public class ProbeOccErrorService {
    public ProbeOccErrorService() {
        // Both alternatives exist and are initialized before any live source edit.
        new ReclazzOccFirstException("preload");
        new ReclazzOccSecondException("preload");
    }

    public void fail() {
        throw new ReclazzOccFirstException("reclazz-c67-owned");
    }

    public static class ReclazzOccFirstException extends RuntimeException {
        public ReclazzOccFirstException(String message) { super(message); }
    }
    public static class ReclazzOccSecondException extends RuntimeException {
        public ReclazzOccSecondException(String message) { super(message); }
    }
}
