package com.fotoxplorr.core.metadata

/**
 * Sidecars constructed from the PUBLIC shapes of each tool's format (no real corpus is available
 * in this sandbox, same caveat as XmpSidecarStoreTest): Lightroom's `crs:` develop attributes plus
 * `lr:hierarchicalSubject`, darktable's `darktable:history` Seq of attribute-only `rdf:li`, and
 * digiKam's `digiKam:TagsList`/`MicrosoftPhoto:LastKeywordXMP`. Parameter blobs are made up, not
 * real develop data; what matters is that they sit in namespaces and shapes we do not own.
 */
object SidecarFixtures {

    val lightroom = """<?xpacket begin="${'﻿'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 7.0-c000 1.000000, 0000/00/00-00:00:00">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:xmp="http://ns.adobe.com/xap/1.0/"
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:lr="http://ns.adobe.com/lightroom/1.0/"
    xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
    xmlns:tiff="http://ns.adobe.com/tiff/1.0/"
    xmlns:aux="http://ns.adobe.com/exif/1.0/aux/"
    xmlns:xmpMM="http://ns.adobe.com/xap/1.0/mm/"
    xmlns:stEvt="http://ns.adobe.com/xap/1.0/sType/ResourceEvent#"
   xmp:Rating="3"
   xmp:Label="Red"
   xmp:CreatorTool="Adobe Lightroom Classic 13.0 (Macintosh)"
   xmp:MetadataDate="2024-05-01T10:00:00+02:00"
   tiff:Make="Canon" tiff:Model="Canon EOS R5"
   aux:Lens="RF24-70mm F2.8 L IS USM"
   crs:Version="16.0" crs:ProcessVersion="15.4" crs:WhiteBalance="As Shot"
   crs:Exposure2012="+0.45" crs:Contrast2012="+12" crs:Highlights2012="-35"
   crs:HasSettings="True" crs:CropTop="0.05" crs:CropAngle="1.2">
   <dc:subject><rdf:Bag><rdf:li>Travel</rdf:li><rdf:li>Italy</rdf:li><rdf:li>Duomo</rdf:li></rdf:Bag></dc:subject>
   <lr:hierarchicalSubject><rdf:Bag><rdf:li>Places|Italy|Duomo</rdf:li></rdf:Bag></lr:hierarchicalSubject>
   <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Duomo</rdf:li><rdf:li xml:lang="de">Dom</rdf:li></rdf:Alt></dc:title>
   <dc:creator><rdf:Seq><rdf:li>Ada Photographer</rdf:li></rdf:Seq></dc:creator>
   <crs:ToneCurvePV2012><rdf:Seq><rdf:li>0, 0</rdf:li><rdf:li>128, 140</rdf:li><rdf:li>255, 255</rdf:li></rdf:Seq></crs:ToneCurvePV2012>
   <xmpMM:History><rdf:Seq>
     <rdf:li stEvt:action="saved" stEvt:instanceID="xmp.iid:1" stEvt:when="2024-05-01T10:00:00+02:00" stEvt:softwareAgent="Adobe Lightroom Classic 13.0" stEvt:changed="/metadata"/>
   </rdf:Seq></xmpMM:History>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""

    val darktable = """<?xpacket begin="${'﻿'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="XMP Core 4.4.0-Exiv2">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:exif="http://ns.adobe.com/exif/1.0/"
    xmlns:xmp="http://ns.adobe.com/xap/1.0/"
    xmlns:xmpMM="http://ns.adobe.com/xap/1.0/mm/"
    xmlns:darktable="http://darktable.sf.net/"
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:lr="http://ns.adobe.com/lightroom/1.0/"
   exif:DateTimeOriginal="2024-03-02T14:22:10.000"
   xmp:Rating="1"
   xmpMM:DerivedFrom="IMG_0420.CR3"
   darktable:xmp_version="5"
   darktable:raw_params="0"
   darktable:auto_presets_applied="1"
   darktable:history_end="2"
   darktable:iop_order_version="4">
   <darktable:masks_history>
    <rdf:Seq/>
   </darktable:masks_history>
   <darktable:history>
    <rdf:Seq>
     <rdf:li darktable:num="0" darktable:operation="exposure" darktable:enabled="1" darktable:modversion="6" darktable:params="gz12eJxjYGBgkGAAgRNODFCQ" darktable:multi_name="" darktable:multi_priority="0" darktable:blendop_version="11" darktable:blendop_params="gz11eJxjYGBgkGAAgQ"/>
     <rdf:li darktable:num="1" darktable:operation="colorin" darktable:enabled="1" darktable:modversion="7" darktable:params="gz03eJxjYGBg" darktable:multi_name="" darktable:multi_priority="0" darktable:blendop_version="11" darktable:blendop_params="gz11eJxjYGBgkGAAgQ"/>
    </rdf:Seq>
   </darktable:history>
   <dc:subject><rdf:Bag><rdf:li>Street</rdf:li><rdf:li>darktable|format|raw</rdf:li></rdf:Bag></dc:subject>
   <lr:hierarchicalSubject><rdf:Bag><rdf:li>Street</rdf:li><rdf:li>darktable|format|raw</rdf:li></rdf:Bag></lr:hierarchicalSubject>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""

    val digikam = """<?xpacket begin="${'﻿'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="XMP Core 4.4.0-Exiv2">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:xmp="http://ns.adobe.com/xap/1.0/"
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:digiKam="http://www.digikam.org/ns/1.0/"
    xmlns:MicrosoftPhoto="http://ns.microsoft.com/photo/1.0/"
    xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/"
   xmp:Rating="4"
   xmp:MetadataDate="2023-11-20T09:15:00"
   digiKam:ColorLabel="2"
   digiKam:PickLabel="3"
   photoshop:Urgency="2">
   <dc:description><rdf:Alt><rdf:li xml:lang="x-default">Sunset at the pier</rdf:li></rdf:Alt></dc:description>
   <dc:rights><rdf:Alt><rdf:li xml:lang="x-default">(c) 2023 Bob</rdf:li></rdf:Alt></dc:rights>
   <dc:subject><rdf:Bag><rdf:li>Sea</rdf:li><rdf:li>Sunset</rdf:li></rdf:Bag></dc:subject>
   <digiKam:TagsList><rdf:Seq><rdf:li>Nature/Sea</rdf:li><rdf:li>Nature/Sunset</rdf:li></rdf:Seq></digiKam:TagsList>
   <MicrosoftPhoto:LastKeywordXMP><rdf:Bag><rdf:li>Nature/Sea</rdf:li><rdf:li>Nature/Sunset</rdf:li></rdf:Bag></MicrosoftPhoto:LastKeywordXMP>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
}
