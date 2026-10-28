package com.fotoxplorr.app.vision

/**
 * Every word the colour-vision tools show. One short sentence each (owner feedback #6); a name goes
 * in through a function so the sentence stays whole. The colour-name table is here too, since a
 * name is a word the person reads.
 */
internal object VisionCopy {
    const val TITLE = "Colour vision"

    /** The card's blurb. */
    const val BLURB = "See colours as others do, or tell similar ones apart."

    const val CLOSE = "Close"
    const val BACK = "Back"

    const val PROTAN = "Hard to see red"
    const val DEUTAN = "Hard to see green"
    const val TRITAN = "Hard to see blue"

    const val SEVERITY = "How strong"
    const val SIMULATE = "Show how it looks"
    const val DALTONIZE = "Help me tell colours apart"
    const val DALTONIZE_NOTE = "This changes the colours so they differ more. It is not what others see."
    const val ORIGINAL = "Original"
    const val NAME_A_COLOUR = "Tap a colour to name it."
    const val PATTERNS = "Mark confusable colours with patterns"
    const val NO_CONFUSION = "Nothing here looks alike."

    fun deficiencyLabel(deficiency: Deficiency): String = when (deficiency) {
        Deficiency.PROTAN -> PROTAN
        Deficiency.DEUTAN -> DEUTAN
        Deficiency.TRITAN -> TRITAN
    }

    /** The spoken or shown name of a low-saturation colour, which is a guess. */
    fun seems(name: String): String = "Seems $name"

    fun patternLabel(pattern: HatchPattern): String = when (pattern) {
        HatchPattern.DIAGONAL -> "Diagonal lines"
        HatchPattern.DOTS -> "Dots"
        HatchPattern.CROSS -> "Crosses"
        HatchPattern.HORIZONTAL -> "Horizontal lines"
        HatchPattern.ANTI_DIAGONAL -> "Backward diagonal lines"
        HatchPattern.VERTICAL -> "Vertical lines"
    }

    const val PLATE_DISCLAIMER = "This is a game, not a medical test, and your screen changes the result."
    const val PLATE_QUESTION = "What number do you see?"
    const val PLATE_NOTHING = "I see nothing"
    const val PLATE_ALL_SEEN = "You saw every number."
    const val PLATE_SOME_MISSED = "You missed some numbers."
    const val PLATE_MOST_MISSED = "You missed most numbers."
    const val PLATE_CHECK_SCREEN = "You missed the easy one, so check your screen and try again."

    /** One colour per line as `name|rrggbb`, sRGB. Names are plain words of our own; no brand or standard palette. */
    const val COLOUR_TABLE = """
        black|000000
        charcoal|36454f
        dark grey|4d4d4d
        grey|808080
        light grey|c0c0c0
        pale grey|dedede
        white|ffffff
        off white|f4f0e6
        warm grey|978a84
        cool grey|95a3a6
        blue grey|85a3b2
        grey green|82a37b
        stone|ada587
        taupe|b9a281
        greige|b5a999
        red|e60000
        dark red|840000
        maroon|650021
        burgundy|610023
        crimson|8c000f
        brick|a03623
        rust|a83c09
        terracotta|cb6843
        salmon|ff796c
        coral|fc5a50
        tomato|ef4026
        scarlet|be0119
        cherry|cf0234
        pink|ff81c0
        hot pink|ff028d
        light pink|ffd1df
        pale pink|ffcfdc
        baby pink|ffb7ce
        bubblegum|ff6cb5
        rose|cf6275
        dusty pink|d58a94
        dusty rose|c0737a
        magenta|c20078
        fuchsia|ed0dd9
        raspberry|b00149
        wine|7f0029
        blush|f29e8e
        peach|ffb07c
        orange|ffa00a
        bright orange|ff5b00
        burnt orange|c04e01
        apricot|ffb16d
        amber|feb308
        yellow|ffff14
        pale yellow|ffff84
        light yellow|fffe7a
        pastel yellow|fffe71
        mustard|ceb301
        gold|dbb40c
        golden yellow|fec615
        dark yellow|d5b60a
        lemon|fdff52
        cream|ffffc2
        beige|e6daa6
        tan|d1b26f
        sand|e2ca76
        khaki|aaa662
        butterscotch|fdb147
        ochre|bf9005
        olive yellow|c2b709
        yellow green|c0fb2d
        green|0b8a10
        bright green|01ff07
        dark green|033500
        forest green|06470c
        light green|96f97b
        pale green|c7fdb5
        lime|aaff32
        lime green|89fe05
        olive|6e750e
        olive green|677a04
        moss|769958
        sage|87ae73
        mint|9ffeb0
        mint green|8fff9f
        seafoam|80f9ad
        sea green|53fca1
        emerald|01a049
        jade|1fa774
        teal|029386
        dark teal|014d4e
        pale teal|82cbb2
        turquoise|06c2ac
        aqua|13eac9
        kelly green|02ab2e
        leaf green|5ca904
        grass green|3f9b0b
        fern|63a950
        pea green|8eab12
        army green|4b5d16
        hunter green|0b4008
        avocado|90b134
        spring green|a9f971
        bottle green|044a05
        blue|0f33e6
        bright blue|0000f5
        vivid blue|0165fc
        electric blue|0652ff
        dark blue|00035b
        navy|000080
        midnight blue|020035
        royal blue|0504aa
        light blue|95d0fc
        pale blue|d0fefe
        ice blue|d7fffe
        sky blue|75bbfd
        baby blue|a2cffe
        powder blue|b1d1fc
        cyan|00ffff
        azure|069af3
        cerulean|0485d1
        cobalt|1e488f
        cornflower|6a79f7
        denim|3b638c
        steel blue|5a7d9a
        dusty blue|5a86ad
        slate|516572
        periwinkle|8e82fe
        indigo|380282
        teal blue|01889f
        ocean blue|03719c
        petrol|005f6a
        purple|7e1e9c
        dark purple|35063e
        bright purple|be03fd
        light purple|bf77f6
        pale purple|b790d4
        pastel purple|caa0ff
        dusty purple|825f87
        blue purple|5729ce
        violet|9a0eea
        lavender|c79fef
        lilac|cea2fd
        heather|a484ac
        mauve|ae7181
        plum|580f41
        eggplant|380835
        orchid|c875c4
        grape|6c3461
        brown|874a14
        dark brown|341c02
        light brown|ad8150
        reddish brown|7f2b0a
        greyish brown|7a6a4f
        chocolate|3d1c02
        coffee|a6814c
        chestnut|742802
        sienna|a9561e
        caramel|af6f09
        copper|b66325
        bronze|a87900
        mahogany|4a0100
        sepia|985e2b
        mud|735c12
        dirt|8a6e45
        cocoa|875f42
        camel|c69f59
"""
}
