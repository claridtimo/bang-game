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
        // shared db.default.* credentials)
        ConnectionProvider conprov = new StaticConnectionProvider(ServerConfig.getJDBCConfig());
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
                if (stmt.executeUpdate() == 0) {
                    System.err.println("No such user '" + username + "'.");
                    System.exit(1);
                }
            } finally {
                stmt.close();
            }
            System.out.println("Set birthday for '" + username + "' to " + birthday + ".");
        } finally {
            conprov.releaseConnection(USER_DB_IDENT, false, conn);
            conprov.shutdown();
        }
    }

    /** The connection-provider identifier for the OOO user database (db.userdb.*). */
    protected static final String USER_DB_IDENT = "userdb";
}
