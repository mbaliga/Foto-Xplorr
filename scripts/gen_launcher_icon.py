#!/usr/bin/env python3
"""Writes the Fotoz launcher icon (drawable/ic_launcher_foreground.xml, ic_launcher_monochrome.xml) and the
light/dark preview SVGs in docs/brand/ from one drawing. Run from the repo root: python3 scripts/gen_launcher_icon.py"""
# One drawing, two themes. Coordinates in the 1100 x 1100 artboard of the owner's artwork.
DARK='#231F20'; TAN='#BFB3A6'; BROWN='#6A5546'; GREY='#7F7670'; CREAM='#E0D9CB'; GOLD='#D9A441'; EDGE='#F5F5F7'
def shapes(panel):
    s=[]
    P=lambda pts,f: s.append(('poly',pts,f))
    L=lambda pts,c,w: s.append(('line',pts,c,w))
    # lower frame (behind)
    P([(296,607),(805,607),(830,975),(270,975)], DARK)
    P([(318,618),(783,618),(800,950),(300,950)], TAN)
    P([(415,758),(790,758),(793,870),(415,870)], DARK)
    P([(628,748),(748,748),(748,870),(628,870)], BROWN)
    L([(560,770),(790,770)], BROWN, 4)
    L([(545,780),(545,870)], BROWN, 4)
    P([(300,950),(690,870),(800,870),(800,930),(300,950)], GREY)
    P([(300,930),(800,930),(800,950),(300,950)], TAN)
    # lower parallelogram
    P([(452,524),(702,686),(298,942),(320,608)], TAN)
    P([(452,524),(540,580),(370,640),(362,722),(328,748),(320,608)], GREY)
    P([(540,580),(572,600),(402,675),(398,700),(362,722),(370,640)], CREAM)
    P([(402,675),(560,592),(610,630),(610,688),(395,828)], DARK)
    L([(515,658),(610,600)], BROWN, 3)
    L([(515,658),(515,760)], GREY, 3)
    L([(520,694),(584,654)], GOLD, 6)
    # upper triangle under the panel
    P([(320,422),(684,422),(690,666)], TAN)
    P([(325,422),(540,422),(618,468),(618,618)], DARK)
    P([(618,520),(682,556),(688,662),(618,620)], BROWN)
    L([(450,424),(612,512),(612,468)], BROWN, 4)
    # white edges of the folded strip
    L([(300,423),(690,668)], EDGE, 13)
    L([(684,420),(706,690)], EDGE, 13)
    L([(706,688),(296,944)], EDGE, 13)
    L([(452,524),(320,608),(298,944)], EDGE, 13)
    L([(298,944),(802,944)], EDGE, 10)
    # top panel last
    P([(297,125),(790,125),(830,418),(270,418)], panel)
    return s
def svg(theme):
    bg = '#FFFFFF' if theme=='light' else '#000000'
    panel = '#000000' if theme=='light' else '#FFFFFF'
    out=[f'<svg xmlns="http://www.w3.org/2000/svg" width="1100" height="1100" viewBox="0 0 1100 1100"><rect width="1100" height="1100" rx="{0 if theme=="light" else 95}" fill="{bg}"/>']
    for sh in shapes(panel):
        if sh[0]=='poly': out.append('<polygon points="%s" fill="%s"/>'%(' '.join(f'{x},{y}' for x,y in sh[1]),sh[2]))
        else: out.append('<polyline points="%s" fill="none" stroke="%s" stroke-width="%d" stroke-linejoin="round"/>'%(' '.join(f'{x},{y}' for x,y in sh[1]),sh[2],sh[3]))
    out.append('</svg>'); return '\n'.join(out)
import os
os.makedirs('docs/brand', exist_ok=True)
for t in ('light','dark'): open(f'docs/brand/fotoz-icon-{t}.svg','w').write(svg(t))
R='app/src/main/res'
def path(sh):
    pts=sh[1]; d='M'+' L'.join(f'{x},{y}' for x,y in pts)
    if sh[0]=='poly': return f'        <path android:fillColor="{sh[2]}" android:pathData="{d} Z" />'
    return (f'        <path android:strokeColor="{sh[2]}" android:strokeWidth="{sh[3]}" android:strokeLineJoin="round"\n'
            f'            android:strokeLineCap="round" android:pathData="{d}" />')
HEAD='''<?xml version="1.0" encoding="utf-8"?>
<!--
  {what}
  Redrawn as a vector from the owner's Fotoz artwork (light and dark versions, November 2026); the
  artboard is 1100 x 1100 units. The group shrinks it to 0.66 about the centre so every corner of the
  drawing stays inside the 66 dp safe circle a launcher's mask always keeps. The small
  "asystemofcells / BALIGA" lettering on the top panel is left out: at launcher size it is under a pixel tall.
  scripts/gen_launcher_icon.py writes this file; edit the script, not this file.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="1100"
    android:viewportHeight="1100">
    <group android:pivotX="550" android:pivotY="550" android:scaleX="0.66" android:scaleY="0.66">
'''
TAIL='    </group>\n</vector>\n'
fg=[path(s) for s in shapes('@color/ic_launcher_panel')]
open(f'{R}/drawable/ic_launcher_foreground.xml','w').write(HEAD.format(what='Launcher icon foreground. The top panel reads @color/ic_launcher_panel: black on the light\n  background, white on the dark one (values-night).')+'\n'.join(fg)+'\n'+TAIL)
# Themed (Android 13+) icon: only alpha counts, so keep the silhouette's lines: panel, the folded strip's edges, the frame.
mono=[]
for s in shapes('#FFFFFFFF'):
    if s[0]=='poly' and s[2]=='#FFFFFFFF': mono.append(path(s))
    if s[0]=='line' and s[2]=='#F5F5F7': mono.append(path(('line',s[1],'#FFFFFFFF',max(s[3],16))))
mono.append(path(('line',[(296,607),(805,607),(830,975),(270,975),(296,607)],'#FFFFFFFF',16)))
open(f'{R}/drawable/ic_launcher_monochrome.xml','w').write(HEAD.format(what='Themed-icon layer (Android 13+): the launcher tints it, so only its shape matters.')+'\n'.join(mono)+'\n'+TAIL)
