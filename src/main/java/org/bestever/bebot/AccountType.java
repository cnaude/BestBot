// --------------------------------------------------------------------------
// Copyright (C) 2012-2013 Best-Ever
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation; either version 2
// of the License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// --------------------------------------------------------------------------
package org.bestever.bebot;

public class AccountType {

    public static final int GUEST = 0;
    public static final int REGISTERED = 1;
    public static final int MODERATOR = 2;
    public static final int ADMIN = 4;
    public static final int RCON = 8;

    /**
     * Check if useLevel is greater than or equal
     *
     * @param accountType The user level to check of the account
     * @param types A list of constants (see AccountType enumerations)
     * @return True if one of the types is met, false if none are
     */
    public static boolean isAccountTypeOf(int accountType, int... types) {
        for (int type : types) {
            return (accountType >= type);
        }
        return false;
    }
}
