package com.fotoxplorr.app.metadata

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * A minimal, surgical XMP (RDF/XML) packet editor.
 *
 * "Minimal" describes what this class KNOWS how to set: a handful of Dublin Core and `xmp:`
 * properties, chosen for one photographer-facing metadata panel. It deliberately does not
 * describe what this class PRESERVES, which is everything -- develop settings a raw processor
 * wrote, lens-correction data, custom namespaces this app has never heard of. A photo that
 * already carries real metadata from Lightroom or Capture One and gets nothing more than a
 * caption typed into this app must come out the other side with every one of those fields intact.
 *
 * That is why this is NOT "parse into a model, edit the model, rebuild the packet from it" the
 * way [com.fotoxplorr.app.search.SearchQuery] parses text into a tree. A model can only round-trip
 * what it has a case for; everything else would have to be dropped or, worse, garbled by being
 * forced into a shape it was never in. Instead every operation here mutates the parsed [Document]
 * IN PLACE at the exact node for the one property being read or written, and [serialize] re-emits
 * that same document, untouched nodes included. The risk this avoids is not hypothetical: it is
 * the specific, career-relevant failure of a professional's existing catalogue metadata vanishing
 * because a caption edit round-tripped it through a lossy toolkit.
 *
 * ## Namespace, not prefix
 * A property is identified by (namespace URI, local name), never by the prefix a particular file
 * happens to use for it. `xmp:Rating` and `xap:Rating` are the same property under two prefixes a
 * different tool chose; matching on the string "xmp:Rating" would silently miss the second.
 * [findProperty] and friends walk every `rdf:Description` in the document precisely so a
 * property recorded by ANY prior tool, under ANY prefix, is the one this class edits or reads --
 * never a duplicate sitting beside it.
 *
 * ## Where "the document" comes from when there is no existing XMP
 * [empty] builds the smallest valid skeleton -- one `rdf:Description rdf:about=""` and nothing
 * else -- so a photo with no XMP at all gets a fresh, spec-valid starting point rather than this
 * class inventing its own dialect of one.
 */
class XmpPacket private constructor(private val document: Document) {

    // ---------------------------------------------------------------------
    // Language-alternative text: dc:description, dc:rights
    // ---------------------------------------------------------------------

    /**
     * The "x-default" value of a language-alternative property, or the first alternative present
     * if no default is recorded -- a photo tagged only in one language still has a value here
     * rather than reading as absent because it lacks an explicit "x-default" entry.
     */
    fun langAlt(namespaceUri: String, localName: String): String? {
        val property = findProperty(namespaceUri, localName) ?: return null
        val alt = firstChildElementNs(property, RDF_NS, "Alt")
            ?: return property.textContent?.trim()?.takeIf { it.isNotEmpty() }
        val values = altValues(alt)
        return values[DEFAULT_LANG] ?: values.values.firstOrNull()
    }

    /** Replaces the whole property with a single "x-default" entry. Blank/null removes it. */
    fun setLangAlt(namespaceUri: String, prefix: String, localName: String, value: String?) {
        if (value.isNullOrBlank()) {
            removeProperty(namespaceUri, localName)
            return
        }
        val property = propertyElement(namespaceUri, prefix, localName)
        removeAttributeEverywhere(property.parentNode as? Element, namespaceUri, localName)
        clearChildren(property)
        val alt = appendChild(property, RDF_NS, "rdf", "Alt")
        val li = appendChild(alt, RDF_NS, "rdf", "li")
        li.setAttributeNS(XML_NS, "xml:lang", DEFAULT_LANG)
        li.textContent = value
    }

    // ---------------------------------------------------------------------
    // Ordered list: dc:creator
    // ---------------------------------------------------------------------

    fun seq(namespaceUri: String, localName: String): List<String> = list(namespaceUri, localName)

    fun setSeq(namespaceUri: String, prefix: String, localName: String, values: List<String>) =
        setList(namespaceUri, prefix, localName, "Seq", values)

    // ---------------------------------------------------------------------
    // Unordered set: dc:subject (keywords)
    // ---------------------------------------------------------------------

    fun bag(namespaceUri: String, localName: String): List<String> = list(namespaceUri, localName)

    fun setBag(namespaceUri: String, prefix: String, localName: String, values: List<String>) =
        setList(namespaceUri, prefix, localName, "Bag", values)

    /**
     * Reads whichever ordered-or-unordered container is actually present, rather than insisting
     * on the specific one ([setSeq]/[setBag]) this class itself would have written. A file from
     * another tool that used the "wrong" container kind for a property is still readable; only
     * writing standardises on the conventional shape for that property.
     */
    private fun list(namespaceUri: String, localName: String): List<String> {
        val property = findProperty(namespaceUri, localName) ?: return emptyList()
        val container = firstChildElementNs(property, RDF_NS, "Seq")
            ?: firstChildElementNs(property, RDF_NS, "Bag")
            ?: firstChildElementNs(property, RDF_NS, "Alt")
            ?: return emptyList()
        return childElementsNs(container, RDF_NS, "li").mapNotNull { it.textContent?.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun setList(
        namespaceUri: String,
        prefix: String,
        localName: String,
        containerLocalName: String,
        values: List<String>,
    ) {
        val cleaned = values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) {
            removeProperty(namespaceUri, localName)
            return
        }
        val property = propertyElement(namespaceUri, prefix, localName)
        clearChildren(property)
        val container = appendChild(property, RDF_NS, "rdf", containerLocalName)
        cleaned.forEach { value ->
            appendChild(container, RDF_NS, "rdf", "li").textContent = value
        }
    }

    // ---------------------------------------------------------------------
    // Simple scalar: xmp:Rating
    // ---------------------------------------------------------------------

    /**
     * Checked in BOTH forms a scalar XMP property legally takes: as the attribute-shorthand
     * Adobe's own toolkit prefers for simple values (`rdf:Description xmp:Rating="4"`), and as a
     * full child element ([setLangAlt]'s sibling shape, `<xmp:Rating>4</xmp:Rating>`) some other
     * writers use instead. Both are valid RDF/XML for the identical fact; a reader that checked
     * only one would silently treat the other as "no rating" on plenty of real files.
     */
    fun intValue(namespaceUri: String, localName: String): Int? {
        attributeEverywhere(namespaceUri, localName)?.toIntOrNull()?.let { return it }
        return findProperty(namespaceUri, localName)?.textContent?.trim()?.toIntOrNull()
    }

    /** Writes the attribute-shorthand form and removes any stray element-form copy. Null clears both. */
    fun setIntValue(namespaceUri: String, prefix: String, localName: String, value: Int?) {
        removeProperty(namespaceUri, localName)
        val description = firstDescriptionOrNull()
        removeAttributeEverywhere(description, namespaceUri, localName)
        if (value == null) return
        val target = description ?: createDescription()
        declareNamespace(target, prefix, namespaceUri)
        target.setAttributeNS(namespaceUri, "$prefix:$localName", value.toString())
    }

    // ---------------------------------------------------------------------
    // Serialization
    // ---------------------------------------------------------------------

    /**
     * The packet, wrapped fresh in its own `<?xpacket?>` processing instructions rather than
     * whatever wrapper (if any) the source carried -- regenerating it is simpler than trying to
     * preserve byte-identical framing, and every reader treats the wrapper as disposable
     * scaffolding around the `x:xmpmeta` element, never as data.
     *
     * Every character outside 7-bit ASCII comes out as a numeric character reference (`&#169;`
     * for "©") rather than the literal UTF-8 byte -- this is what actually makes the packet safe
     * to hand to `ExifInterface.setAttribute(TAG_XMP, ...)`. That call has exactly one string
     * setter for every tag it knows, `setAttribute(String, String)`, with no raw-bytes
     * counterpart (confirmed against the real 1.4.1 jar -- `getAttributeBytes` exists, no setter
     * does), and it silently corrupts any non-ASCII character passed through it to `?`, verified
     * with UTF-8 forced at every layer this app controls (`file.encoding`, `LANG`, JVM args) to
     * rule out a local sandbox artifact before writing this comment. A numeric character
     * reference is pure ASCII text -- digits, `&`, `#`, `;` -- so it passes through untouched,
     * and it is standard XML: [XmpPacket.parse] needs no matching decode step because every
     * compliant XML parser, this one included, already expands `&#169;` back to "©" while
     * parsing. Escaping here is what lets a professional's "José García" or "© 2026" survive a
     * real file, not just an in-memory round trip -- see [MetadataWriterTest] for the failure
     * this replaced.
     */
    fun serialize(): String {
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }
        val writer = StringWriter()
        transformer.transform(DOMSource(document), StreamResult(writer))
        return buildString {
            append(XPACKET_BEGIN)
            append(escapeNonAsciiAsCharacterReferences(writer.toString().trim()))
            append('\n')
            append(XPACKET_END)
        }
    }

    private fun escapeNonAsciiAsCharacterReferences(text: String): String {
        // codePoints(), not a plain Char loop: a character outside the Basic Multilingual Plane
        // is one Unicode codepoint stored as a SURROGATE PAIR of two Kotlin/Java Chars, and
        // walking Chars would treat that pair as two independent, unpaired, individually-invalid
        // codepoints instead of the one real character they together represent.
        val hasNonAscii = text.any { it.code > 127 }
        if (!hasNonAscii) return text
        return buildString {
            text.codePoints().forEach { codePoint ->
                if (codePoint <= 127) appendCodePoint(codePoint) else append("&#").append(codePoint).append(';')
            }
        }
    }

    // ---------------------------------------------------------------------
    // DOM plumbing
    // ---------------------------------------------------------------------

    /** Every `rdf:Description` in the document, regardless of how deep or how many there are. */
    private fun descriptions(): List<Element> {
        val nodes = document.getElementsByTagNameNS(RDF_NS, "Description")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun firstDescriptionOrNull(): Element? = descriptions().firstOrNull()

    private fun createDescription(): Element {
        val rdfRoot = firstChildElementNs(document.documentElement, RDF_NS, "RDF")
            ?: run {
                val root = appendChild(document.documentElement, RDF_NS, "rdf", "RDF")
                declareNamespace(root, "rdf", RDF_NS)
                root
            }
        val description = appendChild(rdfRoot, RDF_NS, "rdf", "Description")
        description.setAttributeNS(RDF_NS, "rdf:about", "")
        return description
    }

    /** The element for (namespace, localName) across every Description, wherever it lives. */
    private fun findProperty(namespaceUri: String, localName: String): Element? =
        descriptions().firstNotNullOfOrNull { description ->
            childElementsNs(description, namespaceUri, localName).firstOrNull()
        }

    /** Finds the property, or creates it (with its namespace declared) inside the first Description. */
    private fun propertyElement(namespaceUri: String, prefix: String, localName: String): Element {
        findProperty(namespaceUri, localName)?.let { return it }
        val description = firstDescriptionOrNull() ?: createDescription()
        val property = appendChild(description, namespaceUri, prefix, localName)
        declareNamespace(property, prefix, namespaceUri)
        return property
    }

    private fun removeProperty(namespaceUri: String, localName: String) {
        findProperty(namespaceUri, localName)?.let { it.parentNode.removeChild(it) }
    }

    private fun attributeEverywhere(namespaceUri: String, localName: String): String? =
        descriptions().firstNotNullOfOrNull { description ->
            description.getAttributeNS(namespaceUri, localName).takeIf { it.isNotEmpty() }
        }

    private fun removeAttributeEverywhere(scope: Element?, namespaceUri: String, localName: String) {
        val targets = if (scope != null) listOf(scope) else descriptions()
        targets.forEach { it.removeAttributeNS(namespaceUri, localName) }
    }

    private fun declareNamespace(element: Element, prefix: String, namespaceUri: String) {
        val attributeName = "xmlns:$prefix"
        if (element.getAttributeNS(XMLNS_NS, prefix).isEmpty()) {
            element.setAttributeNS(XMLNS_NS, attributeName, namespaceUri)
        }
    }

    private fun appendChild(parent: Element, namespaceUri: String, prefix: String, localName: String): Element {
        val child = document.createElementNS(namespaceUri, "$prefix:$localName")
        parent.appendChild(child)
        return child
    }

    private fun clearChildren(element: Element) {
        while (element.firstChild != null) element.removeChild(element.firstChild)
    }

    private fun firstChildElementNs(parent: Element, namespaceUri: String, localName: String): Element? =
        childElementsNs(parent, namespaceUri, localName).firstOrNull()

    private fun childElementsNs(parent: Element, namespaceUri: String, localName: String): List<Element> {
        val out = ArrayList<Element>()
        var node: Node? = parent.firstChild
        while (node != null) {
            if (node is Element && node.namespaceURI == namespaceUri && node.localName == localName) out += node
            node = node.nextSibling
        }
        return out
    }

    /** xml:lang -> text, for every `rdf:li` inside an `rdf:Alt`. Missing `xml:lang` reads as "x-default". */
    private fun altValues(alt: Element): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        childElementsNs(alt, RDF_NS, "li").forEach { li ->
            val lang = li.getAttributeNS(XML_NS, "lang").ifEmpty { DEFAULT_LANG }
            li.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { out[lang] = it }
        }
        return out
    }

    companion object {
        const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        const val DC_NS = "http://purl.org/dc/elements/1.1/"
        const val XMP_NS = "http://ns.adobe.com/xap/1.0/"
        private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
        private const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"
        private const val DEFAULT_LANG = "x-default"

        // "begin" traditionally carries a literal BOM byte, for a tool that byte-sniffs a
        // packet's encoding straight off disk before any XML parsing happens. Left EMPTY here,
        // deliberately -- the spec-sanctioned form for when the encoding is already known by
        // other means (Adobe's own XMP spec names this exact case), which is exactly this app's
        // situation: this string arrives already-decoded, out of a Java `String`-returning tag
        // accessor, never as raw bytes a reader would need to sniff. A literal BOM here would
        // also be the one non-ASCII byte in an otherwise pure-ASCII packet -- see serialize()'s
        // own doc for why every OTHER character earns that treatment through escaping; the
        // wrapper is simpler to fix by just not needing the byte in the first place.
        //
        // The GUID in "id" is the fixed value every XMP-writing tool uses to identify the
        // wrapper format itself, not this file, so it is a shared constant rather than something
        // generated per packet.
        private const val XPACKET_BEGIN = "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"

        // "w" (writable): tells a reader this packet MAY be edited in place by tools that pad for
        // it. This class does not pad -- see the class doc's serialize() note -- so an editor
        // that relies on in-place growth will fall back to its own full rewrite, which every real
        // one already knows how to do; there is nothing here for that fallback to trip over.
        private const val XPACKET_END = "<?xpacket end=\"w\"?>"

        fun empty(): XmpPacket = XmpPacket(newDocument()).also { it.createDescription() }

        /**
         * Parses an existing packet, or returns null if it cannot be understood as XML.
         *
         * Deliberately NOT [empty] on failure. A caller receiving null is expected to skip
         * touching XMP for this file entirely and write only the EXIF side of an edit --
         * see [MetadataWriter]. Returning a blank packet instead would make a well-meaning
         * edit the exact mechanism that destroys metadata this class exists to protect: the
         * write would "succeed" and every field this class does not model would be gone.
         */
        fun parse(text: String): XmpPacket? = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // XMP packets are never expected to reference an external DTD; disabling entity
                // resolution keeps a hostile or malformed file from making the parser reach out
                // to a network address or the filesystem for a document type it names.
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            val builder = factory.newDocumentBuilder()
            XmpPacket(builder.parse(InputSource(StringReader(text))))
        }.getOrNull()

        private fun newDocument(): Document {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val document = factory.newDocumentBuilder().newDocument()
            val meta = document.createElementNS("adobe:ns:meta/", "x:xmpmeta")
            meta.setAttributeNS(XMLNS_NS, "xmlns:x", "adobe:ns:meta/")
            document.appendChild(meta)
            return document
        }
    }
}
