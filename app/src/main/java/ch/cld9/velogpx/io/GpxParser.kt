package ch.cld9.velogpx.io

import ch.cld9.velogpx.model.GpxBounds
import ch.cld9.velogpx.model.GpxCopyright
import ch.cld9.velogpx.model.GpxDocument
import ch.cld9.velogpx.model.GpxIssue
import ch.cld9.velogpx.model.GpxLink
import ch.cld9.velogpx.model.GpxMetadata
import ch.cld9.velogpx.model.GpxParseResult
import ch.cld9.velogpx.model.GpxPerson
import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxRoute
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import ch.cld9.velogpx.model.GpxVersion
import ch.cld9.velogpx.model.IssueSeverity
import ch.cld9.velogpx.model.XmlAttribute
import ch.cld9.velogpx.model.XmlCData
import ch.cld9.velogpx.model.XmlComment
import ch.cld9.velogpx.model.XmlContent
import ch.cld9.velogpx.model.XmlElement
import ch.cld9.velogpx.model.XmlName
import ch.cld9.velogpx.model.XmlText
import org.w3c.dom.CDATASection
import org.w3c.dom.Comment
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class GpxParser(
    private val limits: Limits = Limits(),
) {
    data class Limits(
        val maxBytes: Long = 32L * 1024L * 1024L,
        val maxPoints: Int = 2_000_000,
        val maxExtensionDepth: Int = 64,
    )

    fun parse(input: InputStream, sourceName: String? = null): GpxParseResult {
        val issues = mutableListOf<GpxIssue>()
        val ids = ParseIds()
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { isXIncludeAware = false }
                runCatching { isExpandEntityReferences = false }
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
                runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
            }
            val xml = factory.newDocumentBuilder().parse(
                DoctypeRejectingInputStream(LimitedInputStream(input, limits.maxBytes)),
            )
            val root = xml.documentElement ?: error("The document has no root element")
            if (root.localNameOrNode() != "gpx") error("Expected a <gpx> root element")

            val version = GpxVersion.from(root.attr("version"), root.namespaceURI)
            val namespace = root.namespaceURI
            if (namespace.isNullOrBlank()) {
                issues += GpxIssue(
                    IssueSeverity.WARNING,
                    "MISSING_NAMESPACE",
                    "The file has no GPX namespace; it was imported in compatibility mode.",
                    "/gpx",
                )
            } else if (namespace != version.namespace) {
                issues += GpxIssue(
                    IssueSeverity.WARNING,
                    "VERSION_NAMESPACE_MISMATCH",
                    "GPX version and namespace do not agree; version ${version.value} was inferred.",
                    "/gpx",
                )
            }

            var metadata: GpxMetadata? = null
            val waypoints = mutableListOf<GpxPoint>()
            val routes = mutableListOf<GpxRoute>()
            val tracks = mutableListOf<GpxTrack>()
            val rootExtensions = mutableListOf<XmlElement>()
            var pointCount = 0

            if (version == GpxVersion.V1_0) metadata = parseMetadata10(root, issues)
            for (child in root.elementChildren()) {
                checkCancelled()
                if (!child.isGpxElement()) {
                    rootExtensions += elementToXml(child, 0)
                    continue
                }
                when (child.localNameOrNode()) {
                    "metadata" -> metadata = parseMetadata11(child, issues)
                    "wpt" -> {
                        waypoints += parsePoint(child, issues, "/gpx/wpt", ids)
                        pointCount++
                    }
                    "rte" -> {
                        val route = parseRoute(child, issues, ids)
                        routes += route
                        pointCount += route.points.size
                    }
                    "trk" -> {
                        val track = parseTrack(child, issues, ids)
                        tracks += track
                        pointCount += track.segments.sumOf { it.points.size }
                    }
                    "extensions" -> rootExtensions += parseExtensionContainer(child, 0)
                    "name", "desc", "author", "email", "url", "urlname", "time", "keywords", "bounds" -> Unit
                    else -> rootExtensions += elementToXml(child, 0)
                }
                if (pointCount > limits.maxPoints) error("Point limit of ${limits.maxPoints} exceeded")
            }

            val namespaces = buildMap {
                for (index in 0 until root.attributes.length) {
                    val attr = root.attributes.item(index)
                    when {
                        attr.nodeName == "xmlns" -> put("", attr.nodeValue)
                        attr.prefix == "xmlns" -> put(attr.localName, attr.nodeValue)
                    }
                }
            }
            GpxParseResult(
                GpxDocument(
                    version = version,
                    creator = root.attr("creator") ?: "Unknown",
                    metadata = metadata,
                    waypoints = waypoints,
                    routes = routes,
                    tracks = tracks,
                    rootExtensions = rootExtensions,
                    namespaceDeclarations = namespaces,
                    sourceName = sourceName,
                ),
                issues,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            GpxParseResult(
                null,
                issues + GpxIssue(
                    IssueSeverity.ERROR,
                    "INVALID_GPX",
                    error.message ?: "The GPX document could not be read.",
                ),
            )
        }
    }

    private fun parseMetadata11(element: Element, issues: MutableList<GpxIssue>) = GpxMetadata(
        name = element.textOf("name"),
        description = element.textOf("desc"),
        author = element.child("author")?.let(::parsePerson),
        copyright = element.child("copyright")?.let(::parseCopyright),
        links = element.children("link").map(::parseLink),
        timeText = element.textOf("time"),
        time = parseTime(element.textOf("time"), issues, "/gpx/metadata/time"),
        keywords = element.textOf("keywords"),
        bounds = element.child("bounds")?.let { parseBounds(it, issues, "/gpx/metadata/bounds") },
        extensions = element.child("extensions")?.let { parseExtensionContainer(it, 0) }.orEmpty(),
    )

    private fun parseMetadata10(root: Element, issues: MutableList<GpxIssue>): GpxMetadata? {
        val name = root.textOf("name")
        val description = root.textOf("desc")
        val authorName = root.textOf("author")
        val email = root.textOf("email")
        val url = root.textOf("url")
        val urlName = root.textOf("urlname")
        val timeText = root.textOf("time")
        val keywords = root.textOf("keywords")
        val bounds = root.child("bounds")?.let { parseBounds(it, issues, "/gpx/bounds") }
        if (listOf(name, description, authorName, email, url, timeText, keywords).all { it == null } && bounds == null) return null
        return GpxMetadata(
            name = name,
            description = description,
            author = if (authorName != null || email != null) {
                val parts = email?.split('@', limit = 2)
                GpxPerson(
                    name = authorName,
                    emailId = parts?.getOrNull(0),
                    emailDomain = parts?.getOrNull(1),
                    link = url?.let { GpxLink(it, urlName) },
                )
            } else null,
            links = url?.let { listOf(GpxLink(it, urlName)) }.orEmpty(),
            timeText = timeText,
            time = parseTime(timeText, issues, "/gpx/time"),
            keywords = keywords,
            bounds = bounds,
        )
    }

    private fun parsePerson(element: Element): GpxPerson {
        val email = element.child("email")
        return GpxPerson(
            name = element.textOf("name"),
            emailId = email?.attr("id"),
            emailDomain = email?.attr("domain"),
            link = element.child("link")?.let(::parseLink),
        )
    }

    private fun parseCopyright(element: Element) = GpxCopyright(
        author = element.attr("author").orEmpty(),
        year = element.textOf("year")?.toIntOrNull(),
        license = element.textOf("license"),
    )

    private fun parseLink(element: Element) = GpxLink(
        href = element.attr("href") ?: element.textContent.orEmpty().trim(),
        text = element.textOf("text"),
        type = element.textOf("type"),
    )

    private fun parseRoute(element: Element, issues: MutableList<GpxIssue>, ids: ParseIds): GpxRoute {
        val fields = ElementFields(element)
        return GpxRoute(
            name = fields.textOf("name"),
            comment = fields.textOf("cmt"),
            description = fields.textOf("desc"),
            source = fields.textOf("src"),
            links = linksFrom(fields),
            number = fields.textOf("number")?.toIntOrNull(),
            type = fields.textOf("type"),
            points = fields.children("rtept").mapIndexed { index, point ->
                checkCancelled()
                parsePoint(point, issues, "/gpx/rte/rtept[$index]", ids)
            },
            extensions = extensionsAndForeign(element, ROUTE_CHILDREN, fields.elements),
            id = ids.next("route"),
        )
    }

    private fun parseTrack(element: Element, issues: MutableList<GpxIssue>, ids: ParseIds): GpxTrack {
        val fields = ElementFields(element)
        return GpxTrack(
            name = fields.textOf("name"),
            comment = fields.textOf("cmt"),
            description = fields.textOf("desc"),
            source = fields.textOf("src"),
            links = linksFrom(fields),
            number = fields.textOf("number")?.toIntOrNull(),
            type = fields.textOf("type"),
            segments = fields.children("trkseg").mapIndexed { segmentIndex, segment ->
                checkCancelled()
                val segmentFields = ElementFields(segment)
                GpxTrackSegment(
                    points = segmentFields.children("trkpt").mapIndexed { pointIndex, point ->
                        checkCancelled()
                        parsePoint(point, issues, "/gpx/trk/trkseg[$segmentIndex]/trkpt[$pointIndex]", ids)
                    },
                    extensions = extensionsAndForeign(segment, SEGMENT_CHILDREN, segmentFields.elements),
                    id = ids.next("segment"),
                )
            },
            extensions = extensionsAndForeign(element, TRACK_CHILDREN, fields.elements),
            id = ids.next("track"),
        )
    }

    private fun parsePoint(element: Element, issues: MutableList<GpxIssue>, path: String, ids: ParseIds): GpxPoint {
        checkCancelled()
        val fields = ElementFields(element)
        val latitude = parseCoordinate(element.attr("lat"), -90.0, 90.0, "latitude", issues, path)
        val longitude = parseCoordinate(element.attr("lon"), -180.0, 180.0, "longitude", issues, path)
        val timeText = fields.textOf("time")
        return GpxPoint(
            latitude = latitude,
            longitude = longitude,
            elevation = fields.doubleOf("ele", issues, path),
            timeText = timeText,
            time = parseTime(timeText, issues, "$path/time"),
            magneticVariation = fields.doubleOf("magvar", issues, path),
            geoidHeight = fields.doubleOf("geoidheight", issues, path),
            name = fields.textOf("name"),
            comment = fields.textOf("cmt"),
            description = fields.textOf("desc"),
            source = fields.textOf("src"),
            links = linksFrom(fields),
            symbol = fields.textOf("sym"),
            type = fields.textOf("type"),
            fix = fields.textOf("fix"),
            satellites = fields.textOf("sat")?.toIntOrNull(),
            hdop = fields.doubleOf("hdop", issues, path),
            vdop = fields.doubleOf("vdop", issues, path),
            pdop = fields.doubleOf("pdop", issues, path),
            ageOfDgpsData = fields.doubleOf("ageofdgpsdata", issues, path),
            dgpsId = fields.textOf("dgpsid")?.toIntOrNull(),
            course = fields.doubleOf("course", issues, path),
            speed = fields.doubleOf("speed", issues, path),
            extensions = extensionsAndForeign(element, POINT_CHILDREN, fields.elements),
            id = ids.next("point"),
        )
    }

    private fun linksFrom(element: Element): List<GpxLink> {
        val modern = element.children("link").map(::parseLink)
        if (modern.isNotEmpty()) return modern
        return element.textOf("url")?.let { listOf(GpxLink(it, element.textOf("urlname"))) }.orEmpty()
    }

    private fun linksFrom(fields: ElementFields): List<GpxLink> {
        val modern = fields.children("link").map(::parseLink)
        if (modern.isNotEmpty()) return modern
        return fields.textOf("url")?.let { listOf(GpxLink(it, fields.textOf("urlname"))) }.orEmpty()
    }

    private fun parseBounds(element: Element, issues: MutableList<GpxIssue>, path: String): GpxBounds? = try {
        GpxBounds(
            minLatitude = element.attr("minlat")!!.toDouble(),
            minLongitude = element.attr("minlon")!!.toDouble(),
            maxLatitude = element.attr("maxlat")!!.toDouble(),
            maxLongitude = element.attr("maxlon")!!.toDouble(),
        )
    } catch (_: Exception) {
        issues += GpxIssue(IssueSeverity.WARNING, "INVALID_BOUNDS", "Invalid bounds were ignored.", path)
        null
    }

    private fun parseCoordinate(
        text: String?,
        minimum: Double,
        maximum: Double,
        label: String,
        issues: MutableList<GpxIssue>,
        path: String,
    ): Double {
        val value = text?.toDoubleOrNull()
        if (value == null || !value.isFinite() || value !in minimum..maximum) {
            issues += GpxIssue(IssueSeverity.ERROR, "INVALID_COORDINATE", "Invalid $label '$text'.", path)
            error("Invalid $label at $path")
        }
        return value
    }

    private fun parseTime(text: String?, issues: MutableList<GpxIssue>, path: String): Instant? {
        if (text == null) return null
        return runCatching { Instant.parse(text) }
            .recoverCatching { OffsetDateTime.parse(text).toInstant() }
            .getOrElse {
                issues += GpxIssue(IssueSeverity.WARNING, "INVALID_TIME", "Invalid timestamp '$text' was retained as text.", path)
                null
            }
    }

    private fun extensionsAndForeign(
        element: Element,
        standardNames: Set<String>,
        children: List<Element> = element.elementChildren(),
    ): List<XmlElement> = buildList {
        for (child in children) {
            when {
                child.isGpxElement() && child.localNameOrNode() == "extensions" -> addAll(parseExtensionContainer(child, 0))
                !child.isGpxElement() || child.localNameOrNode() !in standardNames -> add(elementToXml(child, 0))
            }
        }
    }

    private fun parseExtensionContainer(element: Element, depth: Int) =
        element.elementChildren().map { elementToXml(it, depth + 1) }

    private fun elementToXml(element: Element, depth: Int): XmlElement {
        if (depth > limits.maxExtensionDepth) error("Extension nesting limit exceeded")
        val declarations = linkedMapOf<String, String>()
        val attributes = mutableListOf<XmlAttribute>()
        for (index in 0 until element.attributes.length) {
            val attr = element.attributes.item(index)
            when {
                attr.nodeName == "xmlns" -> declarations[""] = attr.nodeValue
                attr.prefix == "xmlns" -> declarations[attr.localName] = attr.nodeValue
                else -> attributes += XmlAttribute(
                    XmlName(attr.namespaceURI, attr.localName ?: attr.nodeName, attr.prefix),
                    attr.nodeValue,
                )
            }
        }
        val children = mutableListOf<XmlContent>()
        for (index in 0 until element.childNodes.length) {
            when (val node = element.childNodes.item(index)) {
                is Element -> children += elementToXml(node, depth + 1)
                is CDATASection -> children += XmlCData(node.data)
                is Comment -> children += XmlComment(node.data)
                else -> if (node.nodeType == Node.TEXT_NODE && node.nodeValue.isNotEmpty()) {
                    children += XmlText(node.nodeValue)
                }
            }
        }
        return XmlElement(
            name = XmlName(element.namespaceURI, element.localNameOrNode(), element.prefix),
            namespaceDeclarations = declarations,
            attributes = attributes,
            children = children,
        )
    }

    private class LimitedInputStream(input: InputStream, private val maximum: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            checkCancelled()
            return super.read().also { if (it >= 0) checked(1) }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkCancelled()
            return super.read(buffer, offset, length).also { if (it > 0) checked(it.toLong()) }
        }
        private fun checked(read: Long) {
            count += read
            if (count > maximum) error("GPX size limit of $maximum bytes exceeded")
        }
    }

    private class DoctypeRejectingInputStream(input: InputStream) : FilterInputStream(input) {
        private val forbidden = "<!DOCTYPE".encodeToByteArray()
        private var matched = 0

        override fun read(): Int = super.read().also { if (it >= 0) inspect(it.toByte()) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { count ->
                if (count > 0) for (index in offset until offset + count) inspect(buffer[index])
            }

        private fun inspect(byte: Byte) {
            val upper = byte.toInt().toChar().uppercaseChar().code.toByte()
            if (upper == forbidden[matched]) {
                matched++
                if (matched == forbidden.size) throw IOException("DOCTYPE declarations are not allowed in GPX files")
            } else {
                matched = if (upper == forbidden[0]) 1 else 0
            }
        }
    }

    companion object {
        private val ROUTE_CHILDREN = setOf("name", "cmt", "desc", "src", "link", "url", "urlname", "number", "type", "extensions", "rtept")
        private val TRACK_CHILDREN = setOf("name", "cmt", "desc", "src", "link", "url", "urlname", "number", "type", "extensions", "trkseg")
        private val SEGMENT_CHILDREN = setOf("trkpt", "extensions")
        private val POINT_CHILDREN = setOf(
            "ele", "time", "magvar", "geoidheight", "name", "cmt", "desc", "src", "link", "url", "urlname",
            "sym", "type", "fix", "sat", "hdop", "vdop", "pdop", "ageofdgpsdata", "dgpsid", "course", "speed", "extensions",
        )
    }

    private class ParseIds {
        private val prefix = UUID.randomUUID().toString()
        private var next = 0L
        fun next(kind: String): String = "$prefix-$kind-${next++}"
    }
}

private class ElementFields(element: Element) {
    val elements: List<Element> = element.elementChildren()
    fun children(name: String): List<Element> = elements.filter { it.isGpxElement() && it.localNameOrNode() == name }
    fun textOf(name: String): String? = elements.firstOrNull { it.isGpxElement() && it.localNameOrNode() == name }?.textContent
    fun doubleOf(name: String, issues: MutableList<GpxIssue>, path: String): Double? {
        val text = textOf(name) ?: return null
        val value = text.toDoubleOrNull()
        if (value == null || !value.isFinite()) {
            issues += GpxIssue(IssueSeverity.WARNING, "INVALID_NUMBER", "Invalid $name '$text' was ignored.", "$path/$name")
            return null
        }
        return value
    }
}

private fun checkCancelled() {
    if (Thread.currentThread().isInterrupted) throw CancellationException("GPX parsing cancelled")
}

private fun Element.localNameOrNode(): String = localName ?: nodeName.substringAfter(':')
private fun Element.attr(name: String): String? = getAttribute(name).takeIf { hasAttribute(name) }
private fun Element.elementChildren(): List<Element> = buildList {
    for (index in 0 until childNodes.length) (childNodes.item(index) as? Element)?.let(::add)
}
private fun Element.isGpxElement(): Boolean {
    val rootNamespace = ownerDocument?.documentElement?.namespaceURI
    return namespaceURI.isNullOrBlank() || namespaceURI == rootNamespace
}
private fun Element.children(name: String): List<Element> = elementChildren().filter { it.isGpxElement() && it.localNameOrNode() == name }
private fun Element.child(name: String): Element? = elementChildren().firstOrNull { it.isGpxElement() && it.localNameOrNode() == name }
private fun Element.textOf(name: String): String? = child(name)?.textContent
private fun Element.doubleOf(name: String, issues: MutableList<GpxIssue>, path: String): Double? {
    val text = textOf(name) ?: return null
    val value = text.toDoubleOrNull()
    if (value == null || !value.isFinite()) {
        issues += GpxIssue(IssueSeverity.WARNING, "INVALID_NUMBER", "Invalid $name '$text' was ignored.", "$path/$name")
        return null
    }
    return value
}
