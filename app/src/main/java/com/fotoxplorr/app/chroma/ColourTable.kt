package com.fotoxplorr.app.chroma

/**
 * The embedded colour names, one `name|RRGGBB` per line.
 *
 * [CSS] is the 139 named colours of the CSS Color specification (a W3C standard that anyone may
 * use), with the spaces put back in and the spelling "grey"; the two names that merely repeat another
 * (aqua = cyan, fuchsia = magenta) are left out so a nearest-name list never shows the same colour
 * twice. [OWN] is a list of everyday colour words in the spirit of the CC0 xkcd colour survey: the
 * words are common English and the hex values were written for this app by eye, not copied from the
 * survey. NO Pantone, RAL, NCS or other trademarked colour system's names or codes appear in either
 * (`ColourNamesTest` checks the names for the system names), and a name here is a word, not a
 * standard: people disagree about colour words, and nearest-in-Lab is a rule, not an authority.
 */
internal object ColourTable {
    val CSS = """
alice blue|F0F8FF
antique white|FAEBD7
aquamarine|7FFFD4
azure|F0FFFF
beige|F5F5DC
bisque|FFE4C4
black|000000
blanched almond|FFEBCD
blue|0000FF
blue violet|8A2BE2
brown|A52A2A
burlywood|DEB887
cadet blue|5F9EA0
chartreuse|7FFF00
chocolate|D2691E
coral|FF7F50
cornflower blue|6495ED
cornsilk|FFF8DC
crimson|DC143C
cyan|00FFFF
dark blue|00008B
dark cyan|008B8B
dark goldenrod|B8860B
dark grey|A9A9A9
dark green|006400
dark khaki|BDB76B
dark magenta|8B008B
dark olive green|556B2F
dark orange|FF8C00
dark orchid|9932CC
dark red|8B0000
dark salmon|E9967A
dark sea green|8FBC8F
dark slate blue|483D8B
dark slate grey|2F4F4F
dark turquoise|00CED1
dark violet|9400D3
deep pink|FF1493
deep sky blue|00BFFF
dim grey|696969
dodger blue|1E90FF
firebrick|B22222
floral white|FFFAF0
forest green|228B22
gainsboro|DCDCDC
ghost white|F8F8FF
gold|FFD700
goldenrod|DAA520
grey|808080
green|008000
green yellow|ADFF2F
honeydew|F0FFF0
hot pink|FF69B4
indian red|CD5C5C
indigo|4B0082
ivory|FFFFF0
khaki|F0E68C
lavender|E6E6FA
lavender blush|FFF0F5
lawn green|7CFC00
lemon chiffon|FFFACD
light blue|ADD8E6
light coral|F08080
light cyan|E0FFFF
light goldenrod yellow|FAFAD2
light grey|D3D3D3
light green|90EE90
light pink|FFB6C1
light salmon|FFA07A
light sea green|20B2AA
light sky blue|87CEFA
light slate grey|778899
light steel blue|B0C4DE
light yellow|FFFFE0
lime|00FF00
lime green|32CD32
linen|FAF0E6
magenta|FF00FF
maroon|800000
medium aquamarine|66CDAA
medium blue|0000CD
medium orchid|BA55D3
medium purple|9370DB
medium sea green|3CB371
medium slate blue|7B68EE
medium spring green|00FA9A
medium turquoise|48D1CC
medium violet red|C71585
midnight blue|191970
mint cream|F5FFFA
misty rose|FFE4E1
moccasin|FFE4B5
navajo white|FFDEAD
navy|000080
old lace|FDF5E6
olive|808000
olive drab|6B8E23
orange|FFA500
orange red|FF4500
orchid|DA70D6
pale goldenrod|EEE8AA
pale green|98FB98
pale turquoise|AFEEEE
pale violet red|DB7093
papaya whip|FFEFD5
peach puff|FFDAB9
peru|CD853F
pink|FFC0CB
plum|DDA0DD
powder blue|B0E0E6
purple|800080
rebecca purple|663399
red|FF0000
rosy brown|BC8F8F
royal blue|4169E1
saddle brown|8B4513
salmon|FA8072
sandy brown|F4A460
sea green|2E8B57
seashell|FFF5EE
sienna|A0522D
silver|C0C0C0
sky blue|87CEEB
slate blue|6A5ACD
slate grey|708090
snow|FFFAFA
spring green|00FF7F
steel blue|4682B4
tan|D2B48C
teal|008080
thistle|D8BFD8
tomato|FF6347
turquoise|40E0D0
violet|EE82EE
wheat|F5DEB3
white|FFFFFF
white smoke|F5F5F5
yellow|FFFF00
yellow green|9ACD32
""".trimIndent()

    val OWN = """
blood red|8A0303
brick red|A23B2A
cherry red|C21E37
fire engine red|D21F2B
raspberry|B3194F
wine|722F37
burgundy|6D1A2A
cranberry|9F1D3F
rust|B5471F
terracotta|C8613F
coral pink|F28B82
watermelon|F4566B
rose pink|F4A6B7
blush|F6D0D0
bubblegum pink|FF8FB8
salmon pink|FA9A8A
dusty rose|C98B94
mauve|B784A7
bright pink|FE3C8C
pale pink|F7D9DC
baby pink|F6C6D2
carnation|F1778F
flamingo|F2776F
strawberry|E6384F
ruby|9B111E
oxblood|4A0E0E
mahogany|6B2A1B
poppy red|E2352B
scarlet|F0281E
vermilion|E5402F
garnet|7D2F37
rosewood|6A1F26
paprika|B03A1F
lipstick red|B5183F
coral red|F0584C
peony|EE8CA8
shell pink|F9D9D0
tangerine|F28500
pumpkin|E8731A
apricot|FBB982
peach|FFB07C
amber|FFBF00
marigold|F4A81D
mustard|CFA32B
saffron|F4B223
sunflower|FFC512
lemon|F4E840
butter|F7E9A0
cream|F4EBD0
banana|FFE135
canary yellow|FFEF3B
custard|F5E06F
sand|D8C197
ochre|C47F1E
honey|E0A526
caramel|B5793B
copper|B3622D
bronze|9C6B30
burnt orange|CC5500
sunset orange|F26A3D
carrot orange|ED7F1D
mango|F7A31C
daffodil|FFE94A
straw|E6D27A
brass|B8A044
olive yellow|B5B03A
primrose|F3E87F
apple green|7DB631
grass green|4C9A2A
leaf green|4FA83D
moss|6E7F3C
olive green|6A7230
sage|9CAF88
pistachio|A3C585
mint|A2E3C0
seafoam|9BE3C6
emerald|169B62
jade|12A573
pine|1F4D36
hunter green|2F5A3A
bottle green|0F5C3C
fern|55823F
avocado|7A8B2D
neon green|3DF22A
shamrock|2BA45B
army green|4A5427
khaki green|8D9150
teal green|12957E
deep sea green|1B6F5F
pea green|8FB32A
lettuce|A4D65E
celery|B5CF77
algae green|5E9E6A
kiwi|8EC63F
spearmint|4DCFA0
viridian|3F8570
malachite|21C95A
clover|3C8F44
jungle|2B7A52
seaweed|40593A
tea green|CFE8BE
eucalyptus|5E8B7A
lichen|9BB08A
baby blue|A5CFEE
ice blue|D0EAF6
periwinkle|8F9BE0
cobalt|0F4EBB
sapphire|1C4FA3
denim|3D5E8C
ocean blue|1E6BA8
cerulean|2D82B5
peacock|0F7490
dusty blue|6E8CAE
teal blue|177A8F
petrol|205060
aqua blue|3CCBE0
electric blue|1E5BFF
ultramarine|2E3192
ink blue|1A2B4C
twilight|4B558F
robin egg blue|74D3D8
lagoon|17A2B8
bright teal|00A6A6
storm blue|506B87
bluebell|6D7CD8
delft blue|2C4B8A
prussian blue|12354F
deep teal|0B5E65
blue grey|70839A
forget me not|82B4EF
blueberry|4A5A9F
lilac|C4A3D4
amethyst|9261B8
grape|5B3A82
eggplant|40264F
wisteria|B3A1DB
heather|A58BB3
royal purple|5E2D91
deep violet|4B1F8F
orchid pink|E58AC9
lavender blue|B9BEF0
mulberry|7B2C5E
dusty purple|80627F
berry|8E2458
iris|6063BD
pink purple|B04FA6
lavender grey|B7B1C5
pale lilac|E1D4EE
dark purple|3D1A56
pastel purple|CBB2E6
chestnut|7B3F26
coffee|5B3C2A
espresso|3A2416
dark chocolate|45281C
cocoa|7B5B45
hazel|8E6C3F
walnut|5C4332
mocha|8A6A54
toffee|AB7B49
camel|C09A6B
fawn|DDB07E
biscuit|E3C79F
oatmeal|D9CDB8
taupe|8D7F73
stone|ABA395
sandstone|D2B88F
ecru|CDBE92
parchment|F1E8CD
eggshell|F1EBDA
champagne|F5E6CC
clay|B3694F
umber|635147
sepia|6F4A2B
driftwood|A38D74
tan brown|A47C52
nutmeg|7F4A32
cinnamon|A3552B
ginger|B2652A
russet|7F4B25
auburn|922B1F
tawny|B5753B
mushroom|B9A99A
latte|C8AA89
khaki brown|8C7853
peat|4A3B2F
charcoal|333A40
graphite|464B50
gunmetal|2E3438
ash grey|B5BDB6
pewter|8F9498
dove grey|A09FA3
cloud|E3E6E8
pearl|EDE8DC
off white|F4F2EA
jet black|0B0B0C
soft black|202020
cool grey|9BA1A8
warm grey|A59F97
chalk|EEECE6
iron|585C60
steel grey|7B8288
mist|D4D8DA
concrete|9C9D99
asphalt|383B3F
""".trimIndent()
}
