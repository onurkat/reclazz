package com.onurkat.reclazz.hybris.impex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Auto-import runs an ImpEx against the live database the moment the file is
 * saved. There is no confirmation and nothing that undoes it, so a REMOVE
 * header is refused unless it was explicitly asked for.
 *
 * The interesting half of this is the false positives. REMOVE is an ordinary
 * English word that turns up in comments, in column values and inside other
 * keywords, and refusing a file because of one of those would break the
 * feature for people importing perfectly safe data.
 */
class ImpexRemoveGuardTest {

    private static int scan(String impex) {
        return ImpexAutoImporter.firstRemoveHeaderLine(impex);
    }

    // ── Caught ────────────────────────────────────────────────────────────

    @Test
    void aRemoveHeaderIsFound() {
        String impex =
                "INSERT_UPDATE Product;code[unique=true];name\n" +
                ";p1;Widget\n" +
                "REMOVE Product;code[unique=true]\n" +
                ";p2\n";
        assertEquals(3, scan(impex), "the REMOVE header is on line 3");
    }

    @Test
    void caseDoesNotMatterBecauseImpexHeadersAreCaseInsensitive() {
        assertEquals(1, scan("remove Product;code[unique=true]\n"));
        assertEquals(1, scan("Remove Product;code[unique=true]\n"));
    }

    @Test
    void anIndentedHeaderIsStillAHeader() {
        assertEquals(2, scan("INSERT Product;code\n\tREMOVE Product;code\n"));
        assertEquals(2, scan("INSERT Product;code\n   REMOVE Product;code\n"));
    }

    @Test
    void theFirstOneIsReported() {
        String impex =
                "INSERT Product;code\n" +
                "REMOVE Product;code\n" +
                "REMOVE Category;code\n";
        assertEquals(2, scan(impex), "the line the user should go and look at is the first");
    }

    // ── Not caught, and must not be ───────────────────────────────────────

    @Test
    void anOrdinaryInsertAndUpdateFileIsUntouched() {
        String impex =
                "INSERT_UPDATE Product;code[unique=true];name\n" +
                ";p1;Widget\n" +
                "UPDATE Category;code[unique=true];name\n" +
                ";c1;Tools\n";
        assertEquals(-1, scan(impex));
    }

    @Test
    void theWordInAValueIsNotAHeader() {
        String impex =
                "INSERT_UPDATE Product;code[unique=true];name\n" +
                ";p1;REMOVE THIS LATER\n" +
                ";p2;Do not remove\n";
        assertEquals(-1, scan(impex), "values start with a semicolon, not with the keyword");
    }

    @Test
    void theWordInACommentIsNotAHeader() {
        String impex =
                "# REMOVE Product once the migration lands\n" +
                "#REMOVE Category;code\n" +
                "INSERT Product;code\n";
        assertEquals(-1, scan(impex), "comments start with #");
    }

    @Test
    void aTypeOrColumnThatMerelyStartsWithTheWordIsNotAHeader() {
        String impex =
                "INSERT_UPDATE RemovalRequest;code[unique=true]\n" +
                ";r1\n" +
                "INSERT_UPDATE Product;removedFlag\n";
        assertEquals(-1, scan(impex),
                "REMOVAL and removedFlag are not the REMOVE mode");
    }

    @Test
    void removeMustBeFollowedByATypeToCount() {
        assertEquals(-1, scan("REMOVE\n"), "a bare word on its own line is not a header");
        assertEquals(-1, scan("REMOVE   \n"));
    }

    @Test
    void emptyInputIsFine() {
        assertEquals(-1, scan(""));
        assertEquals(-1, scan("\n\n\n"));
    }

    /** Windows line endings are normal in this file type. */
    @Test
    void crlfIsHandled() {
        assertEquals(2, scan("INSERT Product;code\r\nREMOVE Product;code\r\n"));
    }
}
