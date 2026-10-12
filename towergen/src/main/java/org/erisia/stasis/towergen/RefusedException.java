package org.erisia.stasis.towergen;

/** The layout is refused (see {@link Refusal}); no tower was built. */
public final class RefusedException extends RuntimeException {
    public final Refusal refusal;

    public RefusedException(Refusal refusal) {
        super(refusal.toString());
        this.refusal = refusal;
    }
}
