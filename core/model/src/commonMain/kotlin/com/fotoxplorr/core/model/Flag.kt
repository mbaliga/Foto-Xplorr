package com.fotoxplorr.core.model

/** ADR-011 §2: `asset_user.flag` (`-1 reject, 0 none, 1 pick`). `rating` and `color_label` stay
 *  plain `Int` columns -- ADR-011 gives this column's exact 3-value meaning but leaves rating as
 *  an unadorned `0..5` range and never specifies a colour set for `color_label` (both are
 *  Phase 4 UI; the columns exist now only so WP1.3's schema doesn't need a later migration to
 *  add them), so there is nothing yet to usefully wrap for those two. */
enum class Flag(val dbValue: Int) {
    REJECT(-1),
    NONE(0),
    PICK(1),
    ;

    companion object {
        fun fromDbValue(value: Int): Flag = entries.first { it.dbValue == value }
    }
}
