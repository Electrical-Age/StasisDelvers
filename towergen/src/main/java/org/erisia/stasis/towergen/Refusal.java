package org.erisia.stasis.towergen;

/**
 * Why the generator refuses a layout: the game must not build it (show the message instead). Two ways to get one:
 * {@link RefusedException} from {@code TowerGen.build} when no tower can be made at all (the stay rule), or
 * {@link TowerGen.Result#refusal} on a tower that was built but does not stand (kept for previews). Use
 * {@link TowerGen#refusalOf} / {@link TowerGen#check} to get either in one place.
 */
public record Refusal(Reason reason, String message) {
    public enum Reason {
        /** A boom's stays can't anchor at or below the next boom level above it (Spine.anchorLevel) with enough rise. */
        STAY_RISE,
        /** The boom library has no valid design for a boom at its stay rise (the offline solve failed for that key). */
        NO_BOOM_DESIGN,
        /** The tower needs members beyond the catalogue (utilisation over the limit after all fallbacks): too tall
         *  for its booms. */
        OVER_CATALOGUE
    }

    @Override public String toString() { return reason + ": " + message; }
}
