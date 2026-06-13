//
// $Id$

package com.threerings.bang.server.tools;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;

import com.samskivert.jdbc.ConnectionProvider;
import com.samskivert.jdbc.StaticConnectionProvider;

import com.threerings.bang.server.ServerConfig;

/**
 * Sets (or updates) a user's birthday in the OOO user database's {@code AUXDATA} table. The
 * auth server reads this birthday to enforce the COPPA age gate
 * (see {@code OOOAuthenticator}); accounts with no aux record are treated as under 13 and are
 * blocked from interactive areas. The {@code createTestUser} Gradle task uses this to give the
 * dev test user a placeholder birthday so it can actually reach the game.
 */
public class SetUserBirthday
{
    public static void main (String[] args)
        throws Exception
    {
        if (args.length != 2) {
            System.err.println("Usage: SetUserBirthday <username> <yyyy-mm-dd>");
            System.exit(1);
        }
        String username = args[0];
        Date birthday = Date.valueOf(args[1]);

        // connect to the user database exactly as the server does (db.userdb.url plus the
        // shared db.default.* credentials), always shutting the provider down afterward
        boolean updated;
        ConnectionProvider conprov = new StaticConnectionProvider(ServerConfig.getJDBCConfig());
        try {
            updated = setBirthday(conprov, username, birthday);
        } finally {
            conprov.shutdown();
        }

        // report (and signal failure) only after the DB resources are released, so the exit
        // never bypasses cleanup
        if (updated) {
            System.out.println("Set birthday for '" + username + "' to " + birthday + ".");
        } else {
            System.err.println("No such user '" + username + "'.");
            System.exit(1);
        }
    }

    /**
     * Upserts the given user's {@code AUXDATA} birthday. Returns false if no such user exists.
     */
    protected static boolean setBirthday (
        ConnectionProvider conprov, String username, Date birthday)
        throws Exception
    {
        Connection conn = conprov.getConnection(USER_DB_IDENT, false);
        try {
            // AUXDATA also carries NOT NULL gender/missive columns, so seed them on insert;
            // a re-run just refreshes the birthday
            PreparedStatement stmt = conn.prepareStatement(
                "insert into AUXDATA (USER_ID, BIRTHDAY, GENDER, MISSIVE) " +
                "select userId, ?, 1, '' from users where username = ? " +
                "on duplicate key update BIRTHDAY = values(BIRTHDAY)");
            try {
                stmt.setDate(1, birthday);
                stmt.setString(2, username);
                return stmt.executeUpdate() > 0;
            } finally {
                stmt.close();
            }
        } finally {
            conprov.releaseConnection(USER_DB_IDENT, false, conn);
        }
    }

    /** The connection-provider identifier for the OOO user database (db.userdb.*). */
    protected static final String USER_DB_IDENT = "userdb";
}
